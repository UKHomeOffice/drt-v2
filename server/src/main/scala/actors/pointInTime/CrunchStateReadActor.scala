package actors.pointInTime

import actors._
import akka.persistence.{RecoveryCompleted, _}
import drt.shared.CrunchApi.{MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import server.protobuf.messages.CrunchState.CrunchDiffMessage
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Staffing._

import scala.collection.immutable._
import scala.language.postfixOps

case object GetCrunchMinutes

class CrunchStateReadActor(snapshotInterval: Int, pointInTime: SDateLike, queues: Map[TerminalName, Seq[QueueName]])
  extends CrunchStateActor(snapshotInterval, "crunch-state", queues, () => pointInTime, 2 * Crunch.oneDayMillis, false) {

  val staffReconstructionRequired: Boolean = pointInTime.millisSinceEpoch <= SDate("2017-12-04").millisSinceEpoch

  override val receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      log.info(s"Received SnapshotOffer ${metadata.timestamp} with ${snapshot.getClass}")
      setStateFromSnapshot(snapshot, Option(pointInTime.addDays(2)))

    case cdm@CrunchDiffMessage(createdAtOption, _, _, _, _, _, _) =>
      createdAtOption match {
        case Some(createdAt) if createdAt <= pointInTime.millisSinceEpoch =>
          log.info(s"Applying crunch diff with createdAt ($createdAt) <= point in time requested: ${pointInTime.millisSinceEpoch}")
          val newState = stateFromDiff(cdm, state)
          state = newState
        case Some(createdAt) =>
          log.info(s"Ignoring crunch diff with createdAt ($createdAt) > point in time requested: ${pointInTime.millisSinceEpoch}")
      }

    case RecoveryCompleted =>
      log.info(s"Recovered successfully")
      state = state.map {
        case PortState(fl, cm, sm) if staffReconstructionRequired =>
          log.info(s"Staff minutes require reconstructing for PortState before 2017-12-04. Attempting to reconstruct")
          val updatedPortState = reconstructStaffMinutes(pointInTime, context, fl, cm)
          log.info(s"Updating port state with ${updatedPortState.staffMinutes.size} staff minutes")
          updatedPortState
        case ps => ps
      }

    case u =>
      log.warn(s"unexpected message: $u")
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved PortState Snapshot")

    case GetState =>
      sender() ! state

    case GetPortState(start: MillisSinceEpoch, end: MillisSinceEpoch) =>
      sender() ! stateForPeriod(start, end)

    case u =>
      log.warn(s"Received unexpected message $u")
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
