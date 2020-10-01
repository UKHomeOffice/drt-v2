package actors.queues

import actors.PartitionedPortStateActor._
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import actors.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate, ProcessNextUpdateRequest}
import actors.queues.QueueLikeActor.UpdatedMillis
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.Terminal
import drt.shared._
import services.SDate

import scala.collection.immutable
import scala.collection.immutable.NumericRange
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

object FlightsRouterActor {

  sealed trait QueryLike extends Ordered[QueryLike] {
    val start: UtcDate

    override def compare(that: QueryLike): Int = start.compare(that.start)
  }

  /**
   * Legacy 1 data is stored in the CrunchStateActor and contains a mix of arrival and crunch data
   *
   * @param date
   */
  case class Legacy1Query(date: UtcDate) extends QueryLike {
    override lazy val start: UtcDate = date
  }

  /**
   * Legacy 2 data is stored in the FlightStateActor and contains only flight data stored in 6 month batches
   *
   * @param date
   */
  case class Legacy2Query(date: UtcDate) extends QueryLike {
    override lazy val start: UtcDate = date
  }

  case class Query(date: UtcDate) extends QueryLike {
    override lazy val start: UtcDate = date
  }

  def utcDateRange(start: SDateLike, end: SDateLike): Seq[UtcDate] = {
    val lookupStartMillis = start.addDays(-2).millisSinceEpoch
    val lookupEndMillis = end.addDays(1).millisSinceEpoch
    val daysRangeMillis = lookupStartMillis to lookupEndMillis by MilliTimes.oneDayMillis
    daysRangeMillis.map(SDate(_).toUtcDate)
  }

  def queryStream(legacy1DateCutoff: UtcDate, legacy2DateCutoff: UtcDate, dates: Seq[UtcDate]): Source[QueryLike, NotUsed] = {
    val grouped: List[QueryLike] = dates
      .groupBy(d => (d < legacy1DateCutoff, d < legacy2DateCutoff))
      .collect {
        case ((isLegacy1, _), dates) if isLegacy1 && dates.nonEmpty =>
          dates.map(Legacy1Query).toList
        case ((_, isLegacy2), dates) if isLegacy2 && dates.nonEmpty =>
          dates.map(Legacy2Query).toList
        case (_, dates) =>
          dates.map(Query).to[immutable.Iterable]
      }
      .flatten.toList

    Source(grouped.sorted)
  }

  def queryStreamForPointInTime(legacy1DateCutoff: UtcDate, legacy2DateCutoff: UtcDate, dates: Seq[UtcDate], pointInTime: SDateLike): Source[QueryLike, NotUsed] = {
    val queries: List[QueryLike] =
      if (pointInTime < SDate(legacy1DateCutoff))
        dates.map(Legacy1Query).toList
      else if (pointInTime < SDate(legacy2DateCutoff))
        dates.map(Legacy2Query).toList
      else dates.map(Query).toList

    Source(queries)
  }

  def scheduledInRange(start: SDateLike, end: SDateLike, scheduled: MillisSinceEpoch): Boolean = {
    val scheduledDate = SDate(scheduled)
    start <= scheduledDate && scheduledDate <= end
  }

  def pcpFallsInRange(start: SDateLike, end: SDateLike, pcpRange: NumericRange[MillisSinceEpoch]): Boolean = {
    val pcpRangeStart = SDate(pcpRange.min)
    val pcpRangeEnd = SDate(pcpRange.max)
    val pcpStartInRange = start <= pcpRangeStart && pcpRangeStart <= end
    val pcpEndInRange = start <= pcpRangeEnd && pcpRangeEnd <= end
    pcpStartInRange || pcpEndInRange
  }

  def flightsByDaySource(flightsLookupByDay: FlightsLookup,
                         flightsLookupByDayLegacy1: FlightsLookup,
                         flightsLookupByDayLegacy2: FlightsLookup,
                         legacy1DataCutoff: UtcDate,
                         legacy2DataCutoff: UtcDate)
                        (start: SDateLike,
                         end: SDateLike,
                         terminal: Terminal,
                         maybePit: Option[MillisSinceEpoch]): Source[FlightsWithSplits, NotUsed] = {
    val dates = utcDateRange(start, end)

    val queries = maybePit match {
      case Some(pointInTime) => queryStreamForPointInTime(legacy1DataCutoff, legacy2DataCutoff, dates, SDate(pointInTime))
      case None => queryStream(legacy1DataCutoff, legacy2DataCutoff, dates)
    }

    queries
      .mapAsync(1) {
        case Query(date) =>
          flightsLookupByDay(terminal, date, maybePit)
        case Legacy1Query(date) =>
          flightsLookupByDayLegacy1(terminal, date, maybePit)
        case query: Legacy2Query =>
          flightsLookupByDayLegacy2(terminal, query.start, maybePit)
      }
      .map {
        case FlightsWithSplits(flights) =>
          FlightsWithSplits(flights.filter { case (_, fws) =>
            val scheduledMatches = scheduledInRange(start, end, fws.apiFlight.Scheduled)
            val pcpMatches = pcpFallsInRange(start, end, fws.apiFlight.pcpRange())
            scheduledMatches || pcpMatches
          })
      }
  }

  def forwardRequestAndKillActor(killActor: ActorRef)(implicit ec: ExecutionContext, timeout: Timeout): (ActorRef, ActorRef, DateRangeLike) => Future[Source[FlightsWithSplits, NotUsed]] =
    (tempActor: ActorRef, replyTo: ActorRef, message: DateRangeLike) => {
      killActor
        .ask(RequestAndTerminate(tempActor, message))
        .mapTo[FlightsWithSplits]
        .map(fwss => Source(List(fwss)))
        .pipeTo(replyTo)
    }

  def runAndCombine(source: Future[Source[FlightsWithSplits, NotUsed]])(implicit mat: ActorMaterializer, ec: ExecutionContext): Future[FlightsWithSplits] = source
    .flatMap(
      _.runWith(Sink.reduce[FlightsWithSplits](_ ++ _))
    )
}

class FlightsRouterActor(
                          updatesSubscriber: ActorRef,
                          terminals: Iterable[Terminal],
                          flightsByDayLookup: FlightsLookup,
                          flightsInRangeLegacy1Lookup: FlightsLookup,
                          flightsInRangeLegacy2Lookup: FlightsLookup,
                          updateFlights: FlightsUpdate,
                          legacy1Date: SDateLike,
                          legacy2Date: SDateLike
                        ) extends Actor with ActorLogging {

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, FlightsWithSplitsDiff)] = List()
  var processingRequest: Boolean = false
  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))
  val forwardRequestAndKillActor: (ActorRef, ActorRef, DateRangeLike) => Future[Source[FlightsWithSplits, NotUsed]] = FlightsRouterActor.forwardRequestAndKillActor(killActor)

  override def receive: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      sender() ! handleAllTerminalLookupsStream(startMillis, endMillis, Option(pit))

    case PointInTimeQuery(pit, GetFlights(startMillis, endMillis)) =>
      self.forward(PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)))

    case PointInTimeQuery(pit, request: DateRangeLike with TerminalRequest) =>
      sender() ! handleLookups(SDate(request.from), SDate(request.to), request.terminal, Option(pit))

    case GetStateForDateRange(startMillis, endMillis) =>
      sender() ! handleAllTerminalLookupsStream(startMillis, endMillis, None)

    case GetFlights(startMillis, endMillis) =>
      self.forward(GetStateForDateRange(startMillis, endMillis))

    case request: DateRangeLike with TerminalRequest =>
      sender() ! handleLookups(SDate(request.from), SDate(request.to), request.terminal, None)

    case container: FlightsWithSplitsDiff =>
      log.info(s"Adding ${container.flightsToUpdate.size} flight updates and ${container.arrivalsToRemove.size} removals to requests queue")
      updateRequestsQueue = (sender(), container) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest

    case ProcessNextUpdateRequest =>
      if (!processingRequest) {
        updateRequestsQueue match {
          case (replyTo, flightsWithSplitsDiff) :: tail =>
            handleUpdatesAndAck(flightsWithSplitsDiff, replyTo)
            updateRequestsQueue = tail
          case Nil =>
            log.debug("Update requests queue is empty. Nothing to do")
        }
      }

    case unexpected => log.warning(s"Got an unexpected message: $unexpected")
  }

  def handleAllTerminalLookupsStream(startMillis: MillisSinceEpoch,
                                     endMillis: MillisSinceEpoch,
                                     maybePit: Option[MillisSinceEpoch]): Source[FlightsWithSplits, NotUsed] =
    Source(terminals.toList)
      .flatMapConcat(terminal => handleLookups(SDate(startMillis), SDate(endMillis), terminal, maybePit))

  def handleUpdatesAndAck(container: FlightsWithSplitsDiff,
                          replyTo: ActorRef): Future[Seq[MillisSinceEpoch]] = {
    processingRequest = true
    val eventualUpdatesDiff = updateByTerminalDayAndGetDiff(container)
    eventualUpdatesDiff
      .map(updatedMillis => updatesSubscriber ! UpdatedMillis(updatedMillis))
      .onComplete { _ =>
        processingRequest = false
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
    eventualUpdatesDiff
  }

  val handleLookups: (SDateLike, SDateLike, Terminal, Option[MillisSinceEpoch]) => Source[FlightsWithSplits, NotUsed] =
    FlightsRouterActor.flightsByDaySource(
      flightsByDayLookup,
      flightsInRangeLegacy1Lookup,
      flightsInRangeLegacy2Lookup,
      legacy1Date.toUtcDate,
      legacy2Date.toUtcDate)

  def updateByTerminalDayAndGetDiff(container: FlightsWithSplitsDiff): Future[Seq[MillisSinceEpoch]] = {
    val eventualUpdatedMinutesDiff: Source[Seq[MillisSinceEpoch], NotUsed] =
      Source(groupByTerminalAndDay(container))
        .mapAsync(1) {
          case ((terminal, day), updates) =>
            log.info(s"handleUpdateAndGetDiff($terminal, $day, ${updates.flightsToUpdate.size})")
            handleUpdateAndGetDiff(terminal, day, updates)
        }
    combineEventualDiffsStream(eventualUpdatedMinutesDiff)
  }

  def groupByTerminalAndDay(container: FlightsWithSplitsDiff): Map[(Terminal, UtcDate), FlightsWithSplitsDiff] = {
    val updates: Map[(Terminal, UtcDate), List[ApiFlightWithSplits]] = container.flightsToUpdate
      .groupBy(flightWithSplits => (flightWithSplits.apiFlight.Terminal, SDate(flightWithSplits.apiFlight.Scheduled).toUtcDate))
    val removals: Map[(Terminal, UtcDate), List[UniqueArrival]] = container.arrivalsToRemove
      .groupBy(ua => (ua.terminal, SDate(ua.scheduled).toUtcDate))

    val keys = updates.keys ++ removals.keys
    keys
      .map { terminalDay =>
        val diff = FlightsWithSplitsDiff(updates.getOrElse(terminalDay, List()), removals.getOrElse(terminalDay, List()))
        (terminalDay, diff)
      }
      .toMap
  }

  private def combineEventualDiffsStream(eventualUpdatedMinutesDiff: Source[Seq[MillisSinceEpoch], NotUsed]): Future[Seq[MillisSinceEpoch]] = {
    eventualUpdatedMinutesDiff
      .fold(Seq[MillisSinceEpoch]())(_ ++ _)
      .runWith(Sink.seq)
      .map {
        case containers if containers.nonEmpty => containers.reduce(_ ++ _)
        case _ => Seq[MillisSinceEpoch]()
      }
      .recover {
        case t =>
          log.error(t, "Failed to combine containers")
          Seq[MillisSinceEpoch]()
      }
  }

  def handleUpdateAndGetDiff(terminal: Terminal,
                             day: UtcDate,
                             flightsDiffForTerminalDay: FlightsWithSplitsDiff): Future[Seq[MillisSinceEpoch]] =
    updateFlights(terminal, day, flightsDiffForTerminalDay)
}
