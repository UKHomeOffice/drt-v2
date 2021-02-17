package actors.daily

import actors.queues.QueueLikeActor.UpdatedMillis
import actors.serializers.FlightMessageConversion
import actors.serializers.PortStateMessageConversion.flightsFromMessages
import actors.{GetState, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.{Recovery, SaveSnapshotSuccess, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff, SplitsForArrivals}
import drt.shared.Terminals.Terminal
import drt.shared.dates.UtcDate
import drt.shared.{SDateLike, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{FlightWithSplitsMessage, FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate


object TerminalDayFlightActor {
  def props(terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, None))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, Option(pointInTime)))
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

  val firstMinuteOfDay = SDate(year, month, day, 0, 0)
  val lastMinuteOfDay = firstMinuteOfDay.addDays(1).addMinutes(-1)

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
      val filteredDiff = diff.forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)

      if (diff == filteredDiff)
        log.info(s"Received FlightsWithSplits for persistence")
      else
        logDifferences(diff, filteredDiff)

      updateAndPersistDiffAndAck(filteredDiff)

    case splits: SplitsForArrivals =>
      val diff = splits.diff(state, now().millisSinceEpoch)
      updateAndPersistDiffAndAck(diff)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! state

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.warn(s"Got unexpected message: $m")
  }

  def logDifferences(diff: FlightsWithSplitsDiff, filteredDiff: FlightsWithSplitsDiff): Unit = log.error(
    s"Received flights for wrong day or terminal " +
      s"${diff.flightsToUpdate.size} flights sent in ${filteredDiff.flightsToUpdate.size} persisted," +
      s"${diff.arrivalsToRemove} removals sent in ${filteredDiff.arrivalsToRemove.size} persisted"
  )

  def updateAndPersistDiffAndAck(diff: FlightsWithSplitsDiff): Unit = {
    val (updatedState, minutesToUpdate) = diff.applyTo(state, now().millisSinceEpoch)
    state = updatedState

    val replyToAndMessage = Option(sender(), UpdatedMillis(minutesToUpdate))
    persistAndMaybeSnapshotWithAck(FlightMessageConversion.flightWithSplitsDiffToMessage(diff), replyToAndMessage)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsWithSplitsDiffMessage =>
      maybePointInTime match {
        case Some(pit) if pit < diff.getCreatedAt =>
          log.debug(s"Ignoring diff created more recently than the recovery point in time")
        case _ => handleDiffMessage(diff)
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case FlightsWithSplitsMessage(flightMessages) =>
      setStateFromSnapshot(flightMessages)
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.values)

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
