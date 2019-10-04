package actors.pointInTime

import actors.PortStateMessageConversion.{crunchMinuteFromMessage, flightWithSplitsFromMessage, staffMinuteFromMessage}
import actors.Sizes.oneMegaByte
import actors._
import akka.persistence._
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import server.protobuf.messages.CrunchState._
import services.SDate

case object GetCrunchMinutes

class CrunchStateReadActor(snapshotInterval: Int,
                           pointInTime: SDateLike,
                           expireAfterMillis: Long,
                           queues: Map[TerminalName, Seq[QueueName]],
                           startMillis: MillisSinceEpoch,
                           endMillis: MillisSinceEpoch)
  extends CrunchStateActor(
    initialMaybeSnapshotInterval = Option(snapshotInterval),
    initialSnapshotBytesThreshold = oneMegaByte,
    name = "crunch-state",
    portQueues = queues,
    now = () => pointInTime,
    expireAfterMillis = expireAfterMillis,
    purgePreviousSnapshots = false,
    acceptFullStateUpdates = false,
    forecastMaxMillis = () => endMillis) {

  val staffReconstructionRequired: Boolean = pointInTime.millisSinceEpoch <= SDate("2017-12-04").millisSinceEpoch

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage => setStateFromSnapshot(snapshot, Option(pointInTime.addDays(2)))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case cdm@CrunchDiffMessage(createdAtOption, _, _, _, _, _, _, _) =>
      createdAtOption match {
        case Some(createdAt) if createdAt <= pointInTime.millisSinceEpoch =>
          log.info(s"Applying crunch diff with createdAt (${SDate(createdAt).toISOString()}) <= point in time requested: ${pointInTime.toISOString()}")
          applyDiff(cdm, endMillis)
        case Some(createdAt) =>
          log.info(s"Ignoring crunch diff with createdAt (${SDate(createdAt).toISOString()}) > point in time requested: ${pointInTime.toISOString()}")
      }
  }

  override def postRecoveryComplete(): Unit = logPointInTimeCompleted(pointInTime)

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved PortState Snapshot")

    case GetState =>
      logInfo(s"Received GetState Request (pit: ${pointInTime.toISOString()}")
      sender() ! Option(state)

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

  override def crunchDiffFromMessage(diffMessage: CrunchDiffMessage, x: MillisSinceEpoch): (Seq[UniqueArrival], Seq[ApiFlightWithSplits], Seq[CrunchMinute], Seq[StaffMinute]) = (

    diffMessage.flightsToRemove.collect {
      case m if queues.contains(m.getTerminalName) => uniqueArrivalFromMessage(m)
    },
    diffMessage.flightsToUpdate.collect {
      case m if isInterestingFlightMessage(m) => flightWithSplitsFromMessage(m)
    },
    diffMessage.crunchMinutesToUpdate.collect {
      case m if isInterestingCrunchMinuteMessage(m) => crunchMinuteFromMessage(m)
    },
    diffMessage.staffMinutesToUpdate.collect {
      case m if isInterestingStaffMinuteMessage(m) => staffMinuteFromMessage(m)
    }
  )

  val isInterestingFlightMessage: FlightWithSplitsMessage => Boolean = (fm: FlightWithSplitsMessage) => {
    val flight = fm.getFlight
    queues.contains(flight.getTerminal) && startMillis <= flight.getPcpTime && flight.getPcpTime <= endMillis
  }

  val isInterestingCrunchMinuteMessage: CrunchMinuteMessage => Boolean = (cm: CrunchMinuteMessage) => {
    queues.contains(cm.getTerminalName) && startMillis <= cm.getMinute && cm.getMinute <= endMillis
  }

  val isInterestingStaffMinuteMessage: StaffMinuteMessage => Boolean = (sm: StaffMinuteMessage) => {
    queues.contains(sm.getTerminalName) && startMillis <= sm.getMinute && sm.getMinute <= endMillis
  }
}
