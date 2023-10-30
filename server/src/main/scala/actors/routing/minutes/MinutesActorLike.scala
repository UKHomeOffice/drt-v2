package actors.routing.minutes

import actors.DateRange
import actors.PartitionedPortStateActor.{DateRangeMillisLike, GetStateForDateRange, PointInTimeQuery, TerminalRequest, UtcDateRangeLike}
import actors.persistent.QueueLikeActor
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.routing.{RouterActorLike, RouterActorLike2, SequentialAccessActor}
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer}
import org.slf4j.LoggerFactory
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.arrivals.{FlightsWithSplits, WithTimeAccessor}
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

case class GetStreamingMinutesForTerminalDateRange(terminal: Terminal, start: UtcDate, end: UtcDate) extends UtcDateRangeLike

case class GetStreamingMinutesForDateRange(start: UtcDate, end: UtcDate) extends UtcDateRangeLike

object MinutesActorLike {
  type MinutesLookup[A, B <: WithTimeAccessor] = ((Terminals.Terminal, UtcDate), Option[MillisSinceEpoch]) => Future[Option[MinutesContainer[A, B]]]
  type FlightsLookup = Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[FlightsWithSplits]
  type ManifestLookup = (UtcDate, Option[MillisSinceEpoch]) => Future[VoyageManifests]

  type MinutesUpdate[A, B <: WithTimeAccessor] = ((Terminals.Terminal, UtcDate), MinutesContainer[A, B]) => Future[UpdatedMillis]
  type FlightsUpdate = ((Terminals.Terminal, UtcDate), FlightUpdates) => Future[UpdatedMillis]
  type ManifestsUpdate = (UtcDate, VoyageManifests) => Future[UpdatedMillis]

  case object ProcessNextUpdateRequest

}

object MinutesActorLikeCommon {
  private val log = LoggerFactory.getLogger(getClass)

  def handleLookup[A, B <: WithTimeAccessor](eventualMaybeResult: Future[Option[MinutesContainer[A, B]]])
                                            (implicit ec: ExecutionContext): Future[Option[MinutesContainer[A, B]]] =
    eventualMaybeResult.flatMap {
      case Some(minutes) =>
        log.debug(s"Got some minutes. Sending them")
        Future(Option(minutes))
      case None =>
        log.debug(s"Got no minutes. Sending None")
        Future(None)
    }

  def retrieveTerminalMinutesWithinRangeAsStream[A, B <: WithTimeAccessor](lookup: MinutesLookup[A, B],
                                                                           terminal: Terminal,
                                                                           start: SDateLike,
                                                                           end: SDateLike,
                                                                           maybePointInTime: Option[MillisSinceEpoch]
                                                                          )
                                                                          (implicit ec: ExecutionContext): Source[MinutesContainer[A, B], NotUsed] =
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


  def handleLookups[A, B <: WithTimeAccessor](lookup: MinutesLookup[A, B],
                                              terminal: Terminal,
                                              start: SDateLike,
                                              end: SDateLike,
                                              maybePointInTime: Option[MillisSinceEpoch]
                                             )
                                             (implicit ec: ExecutionContext, mat: Materializer): Future[MinutesContainer[A, B]] = {
    val eventualContainerWithBookmarks: Future[immutable.Seq[MinutesContainer[A, B]]] =
      retrieveTerminalMinutesWithinRangeAsStream(lookup, terminal, start, end, maybePointInTime)
        .log(getClass.getName)
        .runWith(Sink.seq)

    eventualContainerWithBookmarks.map {
      case cs if cs.nonEmpty => cs.reduce(_ ++ _)
      case _ => MinutesContainer.empty[A, B]
    }
  }

  def retrieveTerminalMinutesDateRangeAsStream[A, B <: WithTimeAccessor](lookup: MinutesLookup[A, B],
                                                                         terminal: Terminal,
                                                                         start: UtcDate,
                                                                         end: UtcDate,
                                                                         maybePointInTime: Option[MillisSinceEpoch]
                                                                        )
                                                                        (implicit ec: ExecutionContext, mat: Materializer): Source[(UtcDate, MinutesContainer[A, B]), NotUsed] =
    Source(DateRange(start, end))
      .mapAsync(1) { day =>
        handleLookup(lookup((terminal, day), maybePointInTime)).map(r => (day, r))
      }
      .collect {
        case (date, maybeContainer) =>
          val container = maybeContainer.getOrElse(MinutesContainer.empty[A, B])
          (date, container)
      }

  def handleAllTerminalLookupsStreamMinutesContainer[A, B <: WithTimeAccessor](lookup: MinutesLookup[A, B],
                                                                               terminals: Iterable[Terminal],
                                                                               startMillis: MillisSinceEpoch,
                                                                               endMillis: MillisSinceEpoch,
                                                                               maybePit: Option[MillisSinceEpoch],
                                                                              )
                                                                              (implicit ec: ExecutionContext, mat: Materializer): Future[MinutesContainer[A, B]] = {
    val eventualMinutesForAllTerminals = Source(terminals.toList)
      .mapAsync(1) { terminal =>
        MinutesActorLikeCommon.handleLookups(lookup, terminal, SDate(startMillis), SDate(endMillis), maybePit)
      }
    combineContainerStream(eventualMinutesForAllTerminals)
  }

  def combineContainerStream[A, B <: WithTimeAccessor](containerStream: Source[MinutesContainer[A, B], NotUsed])
                                                      (implicit ec: ExecutionContext, mat: Materializer): Future[MinutesContainer[A, B]] = {
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

  def retrieveMinutesDateRangeAsStream[A, B <: WithTimeAccessor](lookup: MinutesLookup[A, B],
                                                                 terminals: Iterable[Terminal],
                                                                 start: UtcDate,
                                                                 end: UtcDate,
                                                                 maybePointInTime: Option[MillisSinceEpoch]
                                                                )
                                                                (implicit ec: ExecutionContext): Source[(UtcDate, MinutesContainer[A, B]), NotUsed] =
    Source(DateRange(start, end))
      .mapAsync(1) { day =>
        val terminalContainers = terminals.map(terminal => MinutesActorLikeCommon.handleLookup(lookup((terminal, day), maybePointInTime)))
        Future.sequence(terminalContainers).map { terminalContainers =>
          val combined = terminalContainers.foldLeft[MinutesContainer[A, B]](MinutesContainer.empty[A, B]) {
            case (a, b) => a ++ b.getOrElse(MinutesContainer.empty[A, B])
          }
          (day, combined)
        }
      }
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
    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleAllTerminalLookupsStreamMinutesContainer(lookup, terminals, startMillis, endMillis, Option(pit)).foreach(replyTo ! _)

    case PointInTimeQuery(pit, request: DateRangeMillisLike with TerminalRequest) =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleLookups(lookup, request.terminal, SDate(request.from), SDate(request.to), Option(pit)).foreach(replyTo ! _)

    case GetStateForDateRange(startMillis, endMillis) =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleAllTerminalLookupsStreamMinutesContainer(lookup, terminals, startMillis, endMillis, None).foreach(replyTo ! _)

    case GetStreamingMinutesForTerminalDateRange(terminal, start, end) =>
      sender() ! MinutesActorLikeCommon.retrieveTerminalMinutesDateRangeAsStream(lookup, terminal, start, end, None)

    case GetStreamingMinutesForDateRange(start, end) =>
      sender() ! MinutesActorLikeCommon.retrieveMinutesDateRangeAsStream(lookup, terminals, start, end, None)

    case request: DateRangeMillisLike with TerminalRequest =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleLookups(lookup, request.terminal, SDate(request.from), SDate(request.to), None).foreach(replyTo ! _)
  }

  override def partitionUpdates: PartialFunction[MinutesContainer[A, B], Map[(Terminal, UtcDate), MinutesContainer[A, B]]] = {
    case container: MinutesContainer[A, B] => container.minutes
      .groupBy(minuteLike => (minuteLike.terminal, SDate(minuteLike.minute).toUtcDate))
      .view.mapValues(MinutesContainer(_)).toMap
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
    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleAllTerminalLookupsStreamMinutesContainer(lookup, terminals, startMillis, endMillis, Option(pit)).foreach(replyTo ! _)

    case PointInTimeQuery(pit, request: DateRangeMillisLike with TerminalRequest) =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleLookups(lookup, request.terminal, SDate(request.from), SDate(request.to), Option(pit)).foreach(replyTo ! _)

    case GetStateForDateRange(startMillis, endMillis) =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleAllTerminalLookupsStreamMinutesContainer(lookup, terminals, startMillis, endMillis, None).foreach(replyTo ! _)

    case GetStreamingMinutesForTerminalDateRange(terminal, start, end) =>
      sender() ! MinutesActorLikeCommon.retrieveMinutesDateRangeAsStream(lookup, Seq(terminal), start, end, None)

    case request: DateRangeMillisLike with TerminalRequest =>
      val replyTo = sender()
      MinutesActorLikeCommon.handleLookups(lookup, request.terminal, SDate(request.from), SDate(request.to), None).foreach(replyTo ! _)
  }
  override def partitionUpdates: PartialFunction[MinutesContainer[A, B], Map[(Terminal, UtcDate), MinutesContainer[A, B]]] = {
    case container: MinutesContainer[A, B] => container.minutes
      .groupBy(minuteLike => (minuteLike.terminal, SDate(minuteLike.minute).toUtcDate))
      .view.mapValues(MinutesContainer(_)).toMap
  }

  override def updatePartition(partition: (Terminal, UtcDate), updates: MinutesContainer[A, B]): Future[QueueLikeActor.UpdatedMillis] =
    updateMinutes(partition, updates)

}
