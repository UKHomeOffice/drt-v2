package actors.daily

import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.GetState
import akka.actor.Props
import akka.persistence.SaveSnapshotSuccess
import controllers.model.RedListCounts
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage, SplitsForArrivalsMessage}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion.{arrivalsDiffToMessage, flightMessageToApiFlight, flightWithSplitsDiffToMessage, flightWithSplitsFromMessage, splitsForArrivalsFromMessage, splitsForArrivalsToMessage, uniqueArrivalsFromMessages}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.duration.FiniteDuration

object TerminalDayFlightActor {
  def propsWithRemovalsCutoff(terminal: Terminal,
                              date: UtcDate,
                              now: () => SDateLike,
                              cutOff: Option[FiniteDuration],
                              paxFeedSourceOrder: List[FeedSource]): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, None, cutOff, paxFeedSourceOrder))

  def propsPointInTime(terminal: Terminal,
                       date: UtcDate,
                       now: () => SDateLike,
                       pointInTime: MillisSinceEpoch,
                       cutOff: Option[FiniteDuration],
                       paxFeedSourceOrder: List[FeedSource]): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, Option(pointInTime), cutOff, paxFeedSourceOrder))
}

class TerminalDayFlightActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike,
                             override val maybePointInTime: Option[MillisSinceEpoch],
                             maybeRemovalMessageCutOff: Option[FiniteDuration],
                             paxFeedSourceOrder: List[FeedSource],
                            ) extends RecoveryActorLike {

  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString}"
  }

  val firstMinuteOfDay: SDateLike = SDate(year, month, day, 0, 0)
  private val lastMinuteOfDay: SDateLike = firstMinuteOfDay.addDays(1).addMinutes(-1)

  private val maybeRemovalsCutoffTimestamp: Option[MillisSinceEpoch] = maybeRemovalMessageCutOff
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

  def noopUpdates(diff: FlightsWithSplitsDiff): Int = {
    val noopUpdates = diff.flightsToUpdate.filter(fws => state.flights.exists(_._2.apiFlight == fws.apiFlight))
    noopUpdates.size
  }

  override def receiveCommand: Receive = {
    case redListCounts: RedListCounts =>
      val diff: FlightsWithSplitsDiff = redListCountDiffWith(redListCounts.passengers).forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
      updateAndPersistDiffAndAck(diff)

    case arrivalDiff: ArrivalsDiff =>
      val diff = arrivalDiff
        .forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
        .diffWith(state.flights.view.mapValues(_.apiFlight).toMap)
      updateAndPersistDiffAndAck(diff)

    case splits: SplitsForArrivals =>
      println(s"Got splits $splits")
      val diff = splits.diff(state.flights.view.mapValues(_.splits).toMap)
      println(s"Got diff $diff")
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

  private def updateAndPersistDiffAndAck(diff: FlightsWithSplitsDiff): Unit = {
    val (updatedState, minutesToUpdate) = diff.applyTo(state, now().millisSinceEpoch, paxFeedSourceOrder)
    state = updatedState

    val replyToAndMessage = List((sender(), UpdatedMillis(minutesToUpdate)))
    val message = flightWithSplitsDiffToMessage(diff, now().millisSinceEpoch)
    persistAndMaybeSnapshotWithAck(message, replyToAndMessage)
  }

  private def updateAndPersistDiffAndAck(diff: ArrivalsDiff): Unit = {
    val (updatedState, minutesToUpdate) = diff.applyTo(state, now().millisSinceEpoch, paxFeedSourceOrder)
    state = updatedState

    val replyToAndMessage = List((sender(), UpdatedMillis(minutesToUpdate)))
    val message = arrivalsDiffToMessage(diff, now().millisSinceEpoch)
    persistAndMaybeSnapshotWithAck(message, replyToAndMessage)
  }

  private def updateAndPersistDiffAndAck(diff: SplitsForArrivals): Unit = {
    val (updatedState, minutesToUpdate) = diff.applyTo(state, now().millisSinceEpoch, paxFeedSourceOrder)
    state = updatedState

    val replyToAndMessage = List((sender(), UpdatedMillis(minutesToUpdate)))
    val message = splitsForArrivalsToMessage(diff, now().millisSinceEpoch)
    persistAndMaybeSnapshotWithAck(message, replyToAndMessage)
  }

  private def isBeforeCutoff(timestamp: Long): Boolean = maybeRemovalsCutoffTimestamp match {
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

          val incomingFws = updates.map(flightWithSplitsFromMessage).map(fws => (fws.unique, fws)).toMap
          val updateFws: (Option[ApiFlightWithSplits], ApiFlightWithSplits) => Option[ApiFlightWithSplits] = (_, newFws) =>
            Option(newFws.copy(lastUpdated = Option(createdAt)))
          restorer.applyUpdates(incomingFws, updateFws)
      }

    case FlightsDiffMessage(Some(createdAt), removals, updates, _) =>
      maybePointInTime match {
        case Some(pit) if pit < createdAt =>
          log.debug(s"Ignoring diff created more recently than the recovery point in time")
        case _ =>
          if (isBeforeCutoff(createdAt))
            restorer.remove(uniqueArrivalsFromMessages(removals))

          val incomingArrivals = updates.map(flightMessageToApiFlight).map(a => (a.unique, a)).toMap
          val updateFws: (Option[ApiFlightWithSplits], Arrival) => Option[ApiFlightWithSplits] = (maybeFws, incoming) =>
            maybeFws match {
              case Some(fws) => Option(fws.copy(apiFlight = incoming, lastUpdated = Option(createdAt)))
              case None => Option(ApiFlightWithSplits(incoming, Set(), Option(createdAt)))
            }

          restorer.applyUpdates(incomingArrivals, updateFws)
      }

    case msg@SplitsForArrivalsMessage(Some(createdAt), _) =>
      maybePointInTime match {
        case Some(pit) if pit < createdAt =>
          log.debug(s"Ignoring diff created more recently than the recovery point in time")
        case _ =>
          val incomingSplits = splitsForArrivalsFromMessage(msg).splits
          val updateFws: (Option[ApiFlightWithSplits], Set[Splits]) => Option[ApiFlightWithSplits] = (maybeFws, incoming) => {
            maybeFws.map { fws =>
              val updatedSplits = fws.splits.map(s => (s.source, s)).toMap ++ incoming.map(s => (s.source, s))
              fws.copy(splits = updatedSplits.values.toSet, lastUpdated = Option(createdAt))
            }
          }
          restorer.applyUpdates(incomingSplits, updateFws)
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case m: FlightsWithSplitsMessage =>
      val flights = m.flightWithSplits.map(FlightMessageConversion.flightWithSplitsFromMessage)
      restorer.applyUpdates(flights)
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.values)
}
