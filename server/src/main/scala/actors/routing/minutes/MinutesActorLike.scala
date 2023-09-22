package actors.routing.minutes

import actors.DateRange
import actors.PartitionedPortStateActor.{DateRangeMillisLike, GetStateForDateRange, PointInTimeQuery, TerminalRequest, UtcDateRangeLike}
import actors.persistent.QueueLikeActor
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.routing.{RouterActorLike, RouterActorLike2, SequentialAccessActor}
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.arrivals.{FlightsWithSplits, WithTimeAccessor}
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.collection.immutable
import scala.concurrent.Future

case class GetStreamingDesksForTerminalDateRange(terminal: Terminal, start: UtcDate, end: UtcDate) extends UtcDateRangeLike

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
                                                          updateMinutes: MinutesUpdate[A, B]
                                                         ) extends RouterActorLike[MinutesContainer[A, B], (Terminal, UtcDate)] {
  def splitByResource(request: MinutesContainer[A, B]): Map[(Terminal, UtcDate), MinutesContainer[A, B]] = {
    request.minutes.groupBy(m => (m.terminal, SDate(m.minute).toUtcDate)).map {
      case ((terminal, date), minutes) => ((terminal, date), MinutesContainer(minutes))
    }
  }

  val sequentialUpdatesActor: ActorRef = context.actorOf(Props(new SequentialAccessActor(updateMinutes, splitByResource)))

  override def receiveQueries: Receive = {
    case PointInTimeQuery(pit, GetStreamingDesksForTerminalDateRange(terminal, start, end)) =>
      sender() ! retrieveTerminalMinutesDateRangeAsStream(terminal, start, end, Option(pit))

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      val replyTo = sender()
      handleAllTerminalLookupsStream(startMillis, endMillis, Option(pit)).foreach(replyTo ! _)

    case PointInTimeQuery(pit, request: DateRangeMillisLike with TerminalRequest) =>
      val replyTo = sender()
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), Option(pit)).foreach(replyTo ! _)

    case GetStateForDateRange(startMillis, endMillis) =>
      val replyTo = sender()
      handleAllTerminalLookupsStream(startMillis, endMillis, None).foreach(replyTo ! _)

    case GetStreamingDesksForTerminalDateRange(terminal, start, end) =>
      sender() ! retrieveTerminalMinutesDateRangeAsStream(terminal, start, end, None)

    case request: DateRangeMillisLike with TerminalRequest =>
      val replyTo = sender()
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), None).foreach(replyTo ! _)
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

  def retrieveTerminalMinutesWithinRangeAsStream(terminal: Terminal,
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

  def retrieveTerminalMinutesDateRangeAsStream(terminal: Terminal,
                                               start: UtcDate,
                                               end: UtcDate,
                                               maybePointInTime: Option[MillisSinceEpoch]
                                              ): Source[(UtcDate, MinutesContainer[A, B]), NotUsed] =
    Source(DateRange(start, end))
      .mapAsync(1) { day =>
        handleLookup(lookup((terminal, day), maybePointInTime)).map(r => (day, r))
      }
      .collect {
        case (date, maybeContainer) =>
          val container = maybeContainer.getOrElse(MinutesContainer.empty[A, B])
          (date, container)
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
      .groupBy(minuteLike => (minuteLike.terminal, SDate(minuteLike.minute).toUtcDate))
      .view.mapValues(MinutesContainer(_)).toMap
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

abstract class MinutesActorLike2[A, B <: WithTimeAccessor](terminals: Iterable[Terminal],
                                                           lookup: MinutesLookup[A, B],
                                                           updateMinutes: MinutesUpdate[A, B],
                                                           splitByResource: MinutesContainer[A, B] => Map[(Terminal, UtcDate), MinutesContainer[A, B]],
                                                           shouldSendEffects: MinutesContainer[A, B] => Boolean,
                                                          ) extends RouterActorLike2[MinutesContainer[A, B], (Terminal, UtcDate)] {
  override val sequentialUpdatesActor: ActorRef = context.actorOf(Props(new SequentialAccessActor(updateMinutes, splitByResource) {
    override def shouldSendEffectsToSubscribers(request: MinutesContainer[A, B]): Boolean = {
      shouldSendEffects(request)
    }
  }))

  override def receiveQueries: Receive = {
    case PointInTimeQuery(pit, GetStreamingDesksForTerminalDateRange(terminal, startMillis, endMillis)) =>
      sender() ! retrieveTerminalMinutesWithinRangeAsStream(terminal, SDate(startMillis), SDate(endMillis), Option(pit))

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      val replyTo = sender()
      handleAllTerminalLookupsStream(startMillis, endMillis, Option(pit)).foreach(replyTo ! _)

    case PointInTimeQuery(pit, request: DateRangeMillisLike with TerminalRequest) =>
      val replyTo = sender()
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), Option(pit)).foreach(replyTo ! _)

    case GetStateForDateRange(startMillis, endMillis) =>
      val replyTo = sender()
      handleAllTerminalLookupsStream(startMillis, endMillis, None).foreach(replyTo ! _)

    case GetStreamingDesksForTerminalDateRange(terminal, startMillis, endMillis) =>
      sender() ! retrieveTerminalMinutesWithinRangeAsStream(terminal, SDate(startMillis), SDate(endMillis), None)

    case request: DateRangeMillisLike with TerminalRequest =>
      val replyTo = sender()
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), None).foreach(replyTo ! _)
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

  def retrieveTerminalMinutesWithinRangeAsStream(terminal: Terminal,
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
      .groupBy(minuteLike => (minuteLike.terminal, SDate(minuteLike.minute).toUtcDate))
      .view.mapValues(MinutesContainer(_)).toMap
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
