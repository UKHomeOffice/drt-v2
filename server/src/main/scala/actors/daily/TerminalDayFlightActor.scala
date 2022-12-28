package actors.daily

import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.RecoveryActorLike
import actors.persistent.staffing.GetState
import actors.serializers.FlightMessageConversion
import actors.serializers.FlightMessageConversion.{flightWithSplitsFromMessage, uniqueArrivalsFromMessages}
import akka.actor.Props
import akka.persistence.SaveSnapshotSuccess
import controllers.model.RedListCounts
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightCode, VoyageNumberLike}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.duration.FiniteDuration

object TerminalDayFlightActor {
  def props(terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, None, None))

  def propsWithRemovalsCutoff(terminal: Terminal, date: UtcDate, now: () => SDateLike, cutOff: FiniteDuration): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, None, Option(cutOff)))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, Option(pointInTime), None))
}

class TerminalDayFlightActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike,
                             override val maybePointInTime: Option[MillisSinceEpoch],
                             maybeRemovalMessageCutOff: Option[FiniteDuration],
                            ) extends RecoveryActorLike {

  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString()}"
  }

  val firstMinuteOfDay: SDateLike = SDate(year, month, day, 0, 0)
  val lastMinuteOfDay: SDateLike = firstMinuteOfDay.addDays(1).addMinutes(-1)

  val maybeRemovalsCutoffTimestamp: Option[MillisSinceEpoch] = maybeRemovalMessageCutOff
    .map(cutoff => firstMinuteOfDay.addDays(1).addMillis(cutoff.toMillis).millisSinceEpoch)

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d$loggerSuffix")

  val restorer = new ArrivalsRestorer[ApiFlightWithSplits]
  var state: FlightsWithSplits = FlightsWithSplits.empty

  override def persistenceId: String = f"terminal-flights-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)

  override def postRecoveryComplete(): Unit = {
    state = state.copy(flights = restorer.arrivals)
    restorer.finish()
  }

  private def matchesScheduledAndVoyageNumber(fws: ApiFlightWithSplits, scheduled: SDateLike, voyageNumber: VoyageNumberLike) = {
    fws.apiFlight.Scheduled == scheduled.millisSinceEpoch && fws.apiFlight.VoyageNumber.numeric == voyageNumber.numeric
  }

  private def redListCountDiffWith(counts: Iterable[RedListPassengers]): FlightsWithSplitsDiff = {
    counts.foldLeft(FlightsWithSplitsDiff.empty) {
      case (diff, redListPassengers: RedListPassengers) =>
        val (_, voyageNumber, _) = FlightCode.flightCodeToParts(redListPassengers.flightCode)
        state.flights.values.find(matchesScheduledAndVoyageNumber(_, redListPassengers.scheduled, voyageNumber)) match {
          case None => diff
          case Some(fws) =>
            val updatedArrival = fws.apiFlight.copy(RedListPax = Option(redListPassengers.urns.size))
            diff.copy(flightsToUpdate = diff.flightsToUpdate ++ Iterable(fws.copy(apiFlight = updatedArrival, lastUpdated = Option(now().millisSinceEpoch))))
        }
    }
  }

  override def receiveCommand: Receive = {
    case redListCounts: RedListCounts =>
      val stateDiff: FlightsWithSplitsDiff = redListCountDiffWith(redListCounts.passengers).forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
      updateAndPersistDiffAndAck(stateDiff)

    case diff: ArrivalsDiff =>
      val stateDiff = diff
        .diffWith(state, now().millisSinceEpoch)
        .forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
      updateAndPersistDiffAndAck(stateDiff)

    case splits: SplitsForArrivals =>
      val diff = splits.diff(state, now().millisSinceEpoch)
      updateAndPersistDiffAndAck(diff)

    case pax: PaxForArrivals =>
      val diff = pax.diff(state, now().millisSinceEpoch)
      updateAndPersistDiffAndAck(diff)

    case RemoveSplits =>
      val diff = FlightsWithSplitsDiff(state.flights.values.map(_.copy(splits = Set(), lastUpdated = Option(now().millisSinceEpoch))), Seq())
      log.info(s"Removing splits for terminal ${terminal.toString} for day $year-$month%02d-$day%02d")
      updateAndPersistDiffAndAck(diff)

    case GetState =>
      sender() ! state

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.warn(s"Got unexpected message: $m")
  }

  def updateAndPersistDiffAndAck(diff: FlightsWithSplitsDiff): Unit = {
    val (updatedState, minutesToUpdate) = diff.applyTo(state, now().millisSinceEpoch)
    state = updatedState

    val replyToAndMessage = List((sender(), UpdatedMillis(minutesToUpdate)))
    val message = FlightMessageConversion.flightWithSplitsDiffToMessage(diff)
    persistAndMaybeSnapshotWithAck(message, replyToAndMessage)
  }

  def isBeforeCutoff(timestamp: Long): Boolean = maybeRemovalsCutoffTimestamp match {
    case Some(removalsCutoffTimestamp) => timestamp < removalsCutoffTimestamp
    case None => true
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case FlightsWithSplitsDiffMessage(Some(createdAt), removals, updates) =>
      maybePointInTime match {
        case Some(pit) if pit < createdAt =>
          log.debug(s"Ignoring diff created more recently than the recovery point in time")
        case _ =>
          if (isBeforeCutoff(createdAt))
            restorer.remove(uniqueArrivalsFromMessages(removals))
          restorer.applyUpdates(updates.map(flightWithSplitsFromMessage))
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case m: FlightsWithSplitsMessage =>
      val flights = m.flightWithSplits.map(FlightMessageConversion.flightWithSplitsFromMessage)
      restorer.applyUpdates(flights)
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.values)
}
