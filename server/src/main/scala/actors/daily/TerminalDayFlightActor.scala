package actors.daily

import actors.PortStateMessageConversion.flightsFromMessages
import actors.{FlightMessageConversion, GetState, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.{SDateLike, UniqueArrival}
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{FlightWithSplitsMessage, FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate


object TerminalDayFlightActor {
  def props(terminal: Terminal, date: SDateLike, now: () => SDateLike): Props =
    Props(new TerminalDayFlightActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now, None))

  def propsPointInTime(terminal: Terminal, date: SDateLike, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayFlightActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now, Option(pointInTime)))
}

class TerminalDayFlightActor(
                              year: Int,
                              month: Int,
                              day: Int,
                              terminal: Terminal,
                              val now: () => SDateLike,
                              maybePointInTime: Option[MillisSinceEpoch]
                            ) extends RecoveryActorLike {

  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString()}"
  }

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d$loggerSuffix")

  var state: FlightsWithSplits = FlightsWithSplits.empty

  override def persistenceId: String = f"terminal-flights-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria, replayMax = maxSnapshotInterval)
  }

  override def receiveCommand: Receive = {
    case diff: FlightsWithSplitsDiff =>
      log.debug(s"Received FlightsWithSplits for persistence")
      updateAndPersistDiff(diff)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! state

    case m => log.warn(s"Got unexpected message: $m")
  }

  def updateAndPersistDiff(diff: FlightsWithSplitsDiff): Unit = {
    val (updatedState, minutesToUpdate) = diff.applyTo(state, now().millisSinceEpoch)
    state = updatedState

    val replyToAndMessage = Option(sender(), minutesToUpdate)
    persistAndMaybeSnapshot(diffMessageForFlights(diff), replyToAndMessage)
  }

  def diffMessageForFlights(flightsWithSplitsDiff: FlightsWithSplitsDiff): FlightsWithSplitsDiffMessage =
    FlightsWithSplitsDiffMessage(
      createdAt = Option(now().millisSinceEpoch),
      removals = flightsWithSplitsDiff.arrivalsToRemove.map { arrival =>
        val ua = arrival.unique
        UniqueArrivalMessage(Option(ua.number), Option(ua.terminal.toString), Option(ua.scheduled))
      },
      updates = flightsWithSplitsDiff.flightsToUpdate.map(FlightMessageConversion.flightWithSplitsToMessage)
    )

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsWithSplitsDiffMessage => handleDiffMessage(diff)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case FlightsWithSplitsMessage(flightMessages) =>
      log.info(s"Processing snapshot message")
      setStateFromSnapshot(flightMessages)
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.toMap.values)

  def handleDiffMessage(diff: FlightsWithSplitsDiffMessage): Unit = {
    state = state -- diff.removals.map(uniqueArrivalFromMessage)
    state = state ++ flightsFromMessages(diff.updates)
    log.debug(s"Recovery: state contains ${state.flights.size} flights")
  }

  def uniqueArrivalFromMessage(uam: UniqueArrivalMessage): UniqueArrival =
    UniqueArrival(uam.getNumber, uam.getTerminalName, uam.getScheduled)

  def setStateFromSnapshot(flightMessages: Seq[FlightWithSplitsMessage]): Unit = {
    state = FlightsWithSplits(flightsFromMessages(flightMessages))
  }
}
