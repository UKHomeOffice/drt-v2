package actors.routing.minutes

import actors.PartitionedPortStateActor.{DateRangeLike, GetStateForDateRange, PointInTimeQuery, TerminalRequest}
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.persistent.QueueLikeActor
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.routing.RouterActorLike
import akka.NotUsed
import akka.pattern.pipe
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer}
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.FlightsWithSplits
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.dates.UtcDate
import drt.shared.{SDateLike, Terminals, WithTimeAccessor}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable
import scala.concurrent.Future

case class GetStreamingDesksForTerminalDateRange(terminal: Terminal, from: MillisSinceEpoch, to: MillisSinceEpoch) extends DateRangeLike

object MinutesActorLike {
  type MinutesLookup[A, B <: WithTimeAccessor] = ((Terminals.Terminal, UtcDate), Option[MillisSinceEpoch]) => Future[Option[MinutesContainer[A, B]]]
  type FlightsLookup = Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[FlightsWithSplits]
  type ManifestLookup = (UtcDate, Option[MillisSinceEpoch]) => Future[VoyageManifests]

  type MinutesUpdate[A, B <: WithTimeAccessor] = ((Terminals.Terminal, UtcDate), MinutesContainer[A, B]) => Future[UpdatedMillis]
  type FlightsUpdate = ((Terminals.Terminal, UtcDate), FlightUpdates) => Future[UpdatedMillis]
  type ManifestsUpdate = (UtcDate, VoyageManifests) => Future[UpdatedMillis]

  case object ProcessNextUpdateRequest

}

abstract class MinutesActorLike[A, B <: WithTimeAccessor](terminals: Iterable[Terminal],
                                                          lookup: MinutesLookup[A, B],
                                                          updateMinutes: MinutesUpdate[A, B]) extends RouterActorLike[MinutesContainer[A, B], (Terminal, UtcDate)] {
  override def receiveQueries: Receive = {
    case PointInTimeQuery(pit, GetStreamingDesksForTerminalDateRange(terminal, startMillis, endMillis)) =>
      sender() ! retrieveTerminalMinutesWithinRangeAsStream(terminal, SDate(startMillis), SDate(endMillis), Option(pit))

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      handleAllTerminalLookupsStream(startMillis, endMillis, Option(pit)).pipeTo(sender())

    case PointInTimeQuery(pit, request: DateRangeLike with TerminalRequest) =>
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), Option(pit)).pipeTo(sender())

    case GetStateForDateRange(startMillis, endMillis) =>
      handleAllTerminalLookupsStream(startMillis, endMillis, None).pipeTo(sender())

    case GetStreamingDesksForTerminalDateRange(terminal, startMillis, endMillis) =>
      sender() ! retrieveTerminalMinutesWithinRangeAsStream(terminal, SDate(startMillis), SDate(endMillis), None)

    case request: DateRangeLike with TerminalRequest =>
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), None).pipeTo(sender())
  }

  def handleAllTerminalLookupsStream(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, maybePit: Option[MillisSinceEpoch]): Future[MinutesContainer[A, B]] = {
    val eventualMinutesForAllTerminals = Source(terminals.toList)
      .mapAsync(1) { terminal =>
        handleLookups(terminal, SDate(startMillis), SDate(endMillis), maybePit)
      }
    combineContainerStream(eventualMinutesForAllTerminals)
  }

  def handleLookups(terminal: Terminal,
                    start: SDateLike,
                    end: SDateLike,
                    maybePointInTime: Option[MillisSinceEpoch]): Future[MinutesContainer[A, B]] = {
    val eventualContainerWithBookmarks: Future[immutable.Seq[MinutesContainer[A, B]]] =
      retrieveTerminalMinutesWithinRangeAsStream(terminal, start, end, maybePointInTime)
        .log(getClass.getName)
        .runWith(Sink.seq)

    eventualContainerWithBookmarks.map {
      case cs if cs.nonEmpty => cs.reduce(_ ++ _)
      case _ => MinutesContainer.empty[A, B]
    }
  }

  def retrieveTerminalMinutesWithinRangeAsStream(
                                                  terminal: Terminal,
                                                  start: SDateLike,
                                                  end: SDateLike,
                                                  maybePointInTime: Option[MillisSinceEpoch]
                                                ): Source[MinutesContainer[A, B], NotUsed] =
    Source(Crunch.utcDaysInPeriod(start, end).toList)
      .mapAsync(1) { day =>
        handleLookup(lookup((terminal, day), maybePointInTime)).map(r => (day, r))
      }
      .collect {
        case (_, Some(container)) => container.window(start, end)
        case (day, None) =>
          log.debug(s"No minutes found for for $day")
          MinutesContainer.empty[A, B]
      }
      .fold(MinutesContainer[A, B](Seq())) {
        case (soFarContainer, dayContainer) => soFarContainer ++ dayContainer
      }

  def handleLookup(eventualMaybeResult: Future[Option[MinutesContainer[A, B]]]): Future[Option[MinutesContainer[A, B]]] =
    eventualMaybeResult.flatMap {
      case Some(minutes) =>
        log.debug(s"Got some minutes. Sending them")
        Future(Option(minutes))
      case None =>
        log.debug(s"Got no minutes. Sending None")
        Future(None)
    }

  override def partitionUpdates: PartialFunction[MinutesContainer[A, B], Map[(Terminal, UtcDate), MinutesContainer[A, B]]] = {
    case container: MinutesContainer[A, B] => container.minutes
      .groupBy(simMin => (simMin.terminal, SDate(simMin.minute).toUtcDate))
      .mapValues(MinutesContainer(_))
  }

  private def combineContainerStream(containerStream: Source[MinutesContainer[A, B], NotUsed]): Future[MinutesContainer[A, B]] = {
    containerStream
      .fold(MinutesContainer.empty[A, B])(_ ++ _)
      .log(getClass.getName)
      .runWith(Sink.seq)
      .map(_.foldLeft(MinutesContainer.empty[A, B])(_ ++ _))
      .recoverWith {
        case t =>
          log.error("Failed to combine containers", t)
          Future(MinutesContainer.empty[A, B])
      }
  }

  override def updatePartition(partition: (Terminal, UtcDate), updates: MinutesContainer[A, B]): Future[QueueLikeActor.UpdatedMillis] =
    updateMinutes(partition, updates)
}
