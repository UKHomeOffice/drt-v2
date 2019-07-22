package actors.pointInTime

import actors.PortStateMessageConversion.{crunchMinutesFromMessages, flightsFromMessages, staffMinutesFromMessages}
import actors.Sizes.oneMegaByte
import actors._
import akka.persistence._
import drt.shared.CrunchApi.{MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchStateSnapshotMessage}
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Staffing._

import scala.collection.immutable._
import scala.language.postfixOps

case object GetCrunchMinutes

class CrunchStateReadActor(snapshotInterval: Int, pointInTime: SDateLike, expireAfterMillis: Long, queues: Map[TerminalName, Seq[QueueName]])
  extends CrunchStateActor(Option(snapshotInterval), oneMegaByte, "crunch-state", queues, () => pointInTime, expireAfterMillis, false) {

  val staffReconstructionRequired: Boolean = pointInTime.millisSinceEpoch <= SDate("2017-12-04").millisSinceEpoch

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage => setRecoveryStateFromSnapshot(snapshot, Option(pointInTime.addDays(2)))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: CrunchDiffMessage if diff.getCreatedAt <= pointInTime.millisSinceEpoch =>
      recoveryFlights = (recoveryFlights -- diff.flightIdsToRemove) ++ flightsFromMessages(diff.flightsToUpdate, None)
      recoveryCrunchMinutes = recoveryCrunchMinutes ++ crunchMinutesFromMessages(diff.crunchMinutesToUpdate)
      recoveryStaffMinutes = recoveryStaffMinutes ++ staffMinutesFromMessages(diff.staffMinutesToUpdate)
      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
    case CrunchDiffMessage(Some(createdAt), _, _, _, _, _, _) =>
      log.debug(s"Ignoring crunch diff with createdAt (${SDate(createdAt).toISOString()}) > point in time requested: ${pointInTime.toISOString()}")
    case unexpected =>
      log.info(s"Ignoring unexpected recovery message ${unexpected.getClass}")
  }

  override def postRecoveryComplete(): Unit = {
    super.postRecoveryComplete()

    logPointInTimeCompleted(pointInTime)

    state = state.map {
      case PortState(fl, cm, _) if staffReconstructionRequired =>
        log.info(s"Staff minutes require reconstructing for PortState before 2017-12-04. Attempting to reconstruct")
        val updatedPortState = reconstructStaffMinutes(pointInTime, expireAfterMillis, context, fl, cm)
        log.info(s"Updating port state with ${updatedPortState.staffMinutes.size} staff minutes")
        updatedPortState
      case ps => ps
    }
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved PortState Snapshot")

    case GetState =>
      sender() ! state

    case GetPortState(start, end) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetPortStateForTerminal(start, end, terminalName) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriodForTerminal(start, end, terminalName)

    case u =>
      log.error(s"Received unexpected message $u")
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(
      fromSnapshot = criteria,
      replayMax = snapshotInterval)
    log.info(s"recovery: $recovery")
    recovery
  }
}
