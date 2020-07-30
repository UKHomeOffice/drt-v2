package actors.pointInTime

import actors.FlightMessageConversion.flightWithSplitsFromMessage
import actors.PartitionedPortStateActor.{GetFlights, GetFlightsForTerminal, GetStateForDateRange, GetStateForTerminalDateRange}
import actors.PortStateMessageConversion.{crunchMinuteFromMessage, staffMinuteFromMessage}
import actors.Sizes.oneMegaByte
import actors._
import akka.persistence._
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import server.protobuf.messages.CrunchState._
import services.SDate

case class GetCrunchMinutes(terminal: Terminal)

case class GetStaffMinutes(terminal: Terminal)


class CrunchStateReadActor(pointInTime: SDateLike,
                           expireAfterMillis: Int,
                           portQueues: Map[Terminal, Seq[Queue]],
                           startMillis: MillisSinceEpoch,
                           endMillis: MillisSinceEpoch,
                           replayMaxMessages: Int)
  extends CrunchStateActor(
    initialMaybeSnapshotInterval = Option(replayMaxMessages),
    initialSnapshotBytesThreshold = oneMegaByte,
    name = "crunch-state",
    portQueues = portQueues,
    now = () => pointInTime,
    expireAfterMillis = expireAfterMillis,
    purgePreviousSnapshots = false,
    forecastMaxMillis = () => endMillis) with FlightsDataLike {

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage => setStateFromSnapshot(snapshot, Option(pointInTime.addDays(2)))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case cdm@CrunchDiffMessage(createdAtOption, _, _, _, _, _, _, _) =>
      createdAtOption match {
        case Some(createdAt) if createdAt <= pointInTime.millisSinceEpoch =>
          log.debug(s"Applying crunch diff with createdAt (${SDate(createdAt).toISOString()}) <= point in time requested: ${pointInTime.toISOString()}")
          applyRecoveryDiff(cdm, endMillis)
        case Some(createdAt) =>
          log.debug(s"Ignoring crunch diff with createdAt (${SDate(createdAt).toISOString()}) > point in time requested: ${pointInTime.toISOString()}")
      }
  }

  override def postRecoveryComplete(): Unit = {
    super.postRecoveryComplete()
    logPointInTimeCompleted(pointInTime)
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved PortState Snapshot")

    case GetCrunchMinutes(terminal) =>
      log.debug(s"Received GetCrunchMinutes request")
      sender() ! Option(MinutesContainer(state.crunchMinutes.filterKeys(tqm => tqm.terminal == terminal).values))

    case GetStaffMinutes(terminal) =>
      log.debug(s"Received GetStaffMinutes request")
      sender() ! Option(MinutesContainer(state.staffMinutes.filterKeys(tm => tm.terminal == terminal).values))

    case GetStateForDateRange(start, end) =>
      logInfo(s"Received GetStateForDateRange Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetStateForTerminalDateRange(start, end, terminalName) =>
      logInfo(s"Received GetStateForTerminalDateRange Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriodForTerminal(start, end, terminalName)

    case GetFlights(start, end) =>
      logInfo(s"Received GetFlights Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! FlightsWithSplits(stateForPeriod(start, end).flights)

    case GetFlightsForTerminal(start, end, terminalName) =>
      logInfo(s"Received GetFlightsForTerminal Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! FlightsWithSplits(stateForPeriodForTerminal(start, end, terminalName).flights)

    case u =>
      log.error(s"Received unexpected message $u")
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(
      fromSnapshot = criteria,
      replayMax = replayMaxMessages)
    log.info(s"recovery: $recovery")
    recovery
  }

  override def crunchDiffFromMessage(diffMessage: CrunchDiffMessage, x: MillisSinceEpoch): (Seq[UniqueArrival], Seq[ApiFlightWithSplits], Seq[CrunchMinute], Seq[StaffMinute]) = (

    diffMessage.flightsToRemove.collect {
      case m if portQueues.contains(Terminal(m.getTerminalName)) => uniqueArrivalFromMessage(m)
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
    portQueues.contains(Terminal(flight.getTerminal)) && startMillis <= flight.getPcpTime && flight.getPcpTime <= endMillis
  }

  val isInterestingCrunchMinuteMessage: CrunchMinuteMessage => Boolean = (cm: CrunchMinuteMessage) => {
    portQueues.contains(Terminal(cm.getTerminalName)) && startMillis <= cm.getMinute && cm.getMinute <= endMillis
  }

  val isInterestingStaffMinuteMessage: StaffMinuteMessage => Boolean = (sm: StaffMinuteMessage) => {
    portQueues.contains(Terminal(sm.getTerminalName)) && startMillis <= sm.getMinute && sm.getMinute <= endMillis
  }

  def stateForPeriodForTerminal(start: MillisSinceEpoch, end: MillisSinceEpoch, terminalName: Terminal): PortState =
    state.windowWithTerminalFilter(SDate(start), SDate(end), portQueues.filterKeys(_ == terminalName))
}
