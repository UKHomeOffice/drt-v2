package actors.daily

import akka.actor.{ActorRef, Props}
import akka.persistence.SaveSnapshotSuccess
import controllers.model.RedListCounts
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, ForecastFeedSource, HistoricApiFeedSource, MlFeedSource}
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage, SplitsForArrivalsMessage}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.duration.FiniteDuration

object TerminalDayFlightActor {
  def propsWithRemovalsCutoff(terminal: Terminal,
                              date: UtcDate,
                              now: () => SDateLike,
                              cutOff: Option[FiniteDuration],
                              paxFeedSourceOrder: List[FeedSource],
                              terminalSplits: Option[Splits],
                              requestHistoricSplitsActor: Option[ActorRef],
                              requestHistoricPaxActor: Option[ActorRef],
                             ): Props =
    Props(new TerminalDayFlightActor(
      date.year,
      date.month,
      date.day,
      terminal,
      now,
      None,
      cutOff,
      paxFeedSourceOrder,
      terminalSplits,
      requestHistoricSplitsActor,
      requestHistoricPaxActor,
    ))

  def propsPointInTime(terminal: Terminal,
                       date: UtcDate,
                       now: () => SDateLike,
                       pointInTime: MillisSinceEpoch,
                       cutOff: Option[FiniteDuration],
                       paxFeedSourceOrder: List[FeedSource],
                       terminalSplits: Option[Splits],
                      ): Props =
    Props(new TerminalDayFlightActor(
      date.year,
      date.month,
      date.day,
      terminal,
      now,
      Option(pointInTime),
      cutOff,
      paxFeedSourceOrder,
      terminalSplits,
      None,
      None,
    ))
}

class TerminalDayFlightActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike,
                             override val maybePointInTime: Option[MillisSinceEpoch],
                             maybeRemovalMessageCutOff: Option[FiniteDuration],
                             paxFeedSourceOrder: List[FeedSource],
                             terminalSplits: Option[Splits],
                             maybeRequestHistoricSplitsActor: Option[ActorRef],
                             maybeRequestHistoricPaxActor: Option[ActorRef],
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
    state = state.copy(flights = restorer.arrivals.filter(_._1.number != 0))
    restorer.finish()

    if (maybePointInTime.isEmpty && messagesPersistedSinceSnapshotCounter > 10 && lastMinuteOfDay.addDays(1) < now().getUtcLastMidnight) {
      log.info(f"Creating final snapshot for $terminal for historic day $year-$month%02d-$day%02d")
      saveSnapshot(stateToMessage)
    }
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
      val diff = redListCountDiffWith(redListCounts.passengers).forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
      updateAndPersistDiffAndAck(diff)

    case arrivalDiff: ArrivalsDiff =>
      val diff = arrivalDiff
        .forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
        .diff(state.flights.view.mapValues(_.apiFlight).toMap)
      updateAndPersistDiffAndAck(diff)

    case splits: SplitsForArrivals =>
      val diff = splits.diff(state.flights.view.mapValues(_.splits).toMap)
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

    case m => log.error(s"Got unexpected message: $m")
  }

  private def requestMissingHistoricSplits(): Unit =
    maybeRequestHistoricSplitsActor.foreach { requestActor =>
      val missingHistoricSplits = state.flights.values.collect {
        case fws if !fws.apiFlight.Origin.isDomesticOrCta && !fws.splits.exists(_.source == Historical) => fws.unique
      }
      if (missingHistoricSplits.nonEmpty)
        requestActor ! missingHistoricSplits
    }

  private def requestMissingPax(): Unit = {
    maybeRequestHistoricPaxActor.foreach { requestActor =>
      val missingPaxSource = state.flights.values.collect {
        case fws if !fws.apiFlight.Origin.isDomesticOrCta && hasNoForecastPax(fws.apiFlight) => fws.unique
      }
      if (missingPaxSource.nonEmpty)
        requestActor ! missingPaxSource
    }
  }

  private def hasNoForecastPax(arrival: Arrival): Boolean = {
    val existingSources: Set[FeedSource] = arrival.PassengerSources.keySet
    val acceptableSources: Set[FeedSource] = Set(HistoricApiFeedSource, MlFeedSource, ForecastFeedSource)
    val acceptableExistingSources = existingSources.intersect(acceptableSources)
    acceptableExistingSources.isEmpty
  }

  private def applyDiffAndPersist(applyDiff: (FlightsWithSplits, Long, List[FeedSource]) => (FlightsWithSplits, Set[Long])): Set[TerminalUpdateRequest] = {
    val (updatedState, minutesToUpdate) = applyDiff(state, now().millisSinceEpoch, paxFeedSourceOrder)

    state = updatedState

    requestMissingPax()
    requestMissingHistoricSplits()

    minutesToUpdate.map(SDate(_).toLocalDate).map(d => TerminalUpdateRequest(terminal, d))
  }

  private def updateAndPersistDiffAndAck(diff: FlightsWithSplitsDiff): Unit =
    if (diff.nonEmpty) {
      val updateRequests = applyDiffAndPersist(diff.applyTo)
      val message = flightWithSplitsDiffToMessage(diff, now().millisSinceEpoch)
      persistAndMaybeSnapshotWithAck(message, List((sender(), updateRequests)))
    }
    else sender() ! Set.empty

  private def updateAndPersistDiffAndAck(diff: ArrivalsDiff): Unit =
    if (diff.toUpdate.nonEmpty || diff.toRemove.nonEmpty) {
      val updateRequests = applyDiffAndPersist(diff.applyTo)
      val message = arrivalsDiffToMessage(diff, now().millisSinceEpoch)
      persistAndMaybeSnapshotWithAck(message, List((sender(), updateRequests)))
    } else sender() ! Set.empty

  private def updateAndPersistDiffAndAck(diff: SplitsForArrivals): Unit =
    if (diff.splits.nonEmpty) {
      val timestamp = now().millisSinceEpoch
      val updateRequests = applyDiffAndPersist(diff.applyTo)
      val message = splitsForArrivalsToMessage(diff, timestamp)
      persistAndMaybeSnapshotWithAck(message, List((sender(), updateRequests)))
    } else sender() ! Set.empty

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
          val updateFws: (Option[ApiFlightWithSplits], ApiFlightWithSplits) => Option[ApiFlightWithSplits] = (maybeExisting, newFws) =>
            Option(maybeExisting.map(_.update(newFws)).getOrElse(newFws))
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
          val updateFws: (Option[ApiFlightWithSplits], Arrival) => Option[ApiFlightWithSplits] = (maybeExistingFws, incoming) => {
            val updated = maybeExistingFws
              .map(existingFws => existingFws.copy(apiFlight = existingFws.apiFlight.update(incoming)))
              .getOrElse(ApiFlightWithSplits(incoming, terminalSplits.toSet, Option(createdAt)))
              .copy(lastUpdated = Option(createdAt))
            Option(updated)
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
            maybeFws.map(fws => SplitsForArrivals.updateFlightWithSplits(fws, incoming, createdAt))
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
