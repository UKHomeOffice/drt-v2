package actors.queues

import actors.PartitionedPortStateActor._
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import actors.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}
import actors.queues.QueueLikeActor.UpdatedMillis
import actors.routing.RouterActorLikeWithSubscriber
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi._
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import drt.shared.dates.UtcDate
import services.SDate

import scala.collection.immutable.NumericRange
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object FlightsRouterActor {

  sealed trait QueryLike extends Ordered[QueryLike] {
    val date: UtcDate

    override def compare(that: QueryLike): Int = date.compare(that.date)
  }

  case class Query(date: UtcDate) extends QueryLike

  def queryStream(dates: Seq[UtcDate]): Source[QueryLike, NotUsed] = Source(dates.map(Query).toList)

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

  def flightsByDaySource(flightsLookupByDay: FlightsLookup)
                        (start: SDateLike,
                         end: SDateLike,
                         terminal: Terminal,
                         maybePit: Option[MillisSinceEpoch]): Source[FlightsWithSplits, NotUsed] = {
    val dates = DateRange.utcDateRangeWithBuffer(2, 1)(start, end)

    val queries = queryStream(dates)

    queries
      .mapAsync(1) {
        query =>
          flightsLookupByDay(terminal, query.date, maybePit)
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

  def forwardRequestAndKillActor(killActor: ActorRef)
                                (implicit ec: ExecutionContext, timeout: Timeout): (ActorRef, ActorRef, DateRangeLike) => Future[Source[FlightsWithSplits, NotUsed]] =
    (tempActor: ActorRef, replyTo: ActorRef, message: DateRangeLike) => {
      killActor
        .ask(RequestAndTerminate(tempActor, message))
        .mapTo[FlightsWithSplits]
        .map(fwss => Source(List(fwss)))
        .pipeTo(replyTo)
    }

  def runAndCombine(eventualSource: Future[Source[FlightsWithSplits, NotUsed]])
                   (implicit mat: ActorMaterializer, ec: ExecutionContext): Future[FlightsWithSplits] = eventualSource
    .flatMap(source => source
      .log(getClass.getName)
      .runWith(Sink.reduce[FlightsWithSplits](_ ++ _))
    )
}

class FlightsRouterActor(val updatesSubscriber: ActorRef,
                         terminals: Iterable[Terminal],
                         flightsByDayLookup: FlightsLookup,
                         updateFlights: FlightsUpdate
                        ) extends RouterActorLikeWithSubscriber[FlightUpdates, (Terminal, UtcDate)] {
  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()), "flights-router-actor-kill-actor")
  val forwardRequestAndKillActor: (ActorRef, ActorRef, DateRangeLike) => Future[Source[FlightsWithSplits, NotUsed]] =
    FlightsRouterActor.forwardRequestAndKillActor(killActor)

  override def receiveQueries: Receive = {
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
  }

  def handleAllTerminalLookupsStream(startMillis: MillisSinceEpoch,
                                     endMillis: MillisSinceEpoch,
                                     maybePit: Option[MillisSinceEpoch]): Source[FlightsWithSplits, NotUsed] =
    Source(terminals.toList)
      .flatMapConcat(terminal => handleLookups(SDate(startMillis), SDate(endMillis), terminal, maybePit))

  val handleLookups: (SDateLike, SDateLike, Terminal, Option[MillisSinceEpoch]) => Source[FlightsWithSplits, NotUsed] =
    FlightsRouterActor.flightsByDaySource(flightsByDayLookup)

  override def partitionUpdates: PartialFunction[FlightUpdates, Map[(Terminal, UtcDate), FlightUpdates]] = {
    case container: SplitsForArrivals =>
      container.splits
        .groupBy {
          case (uniqueArrival, _) => (uniqueArrival.terminal, SDate(uniqueArrival.scheduled).toUtcDate)
        }
        .map {
          case (terminalDay, allSplits) => (terminalDay, SplitsForArrivals(allSplits))
        }

    case container: ArrivalsDiff =>
      val updates: Map[(Terminal, UtcDate), Iterable[Arrival]] = container.toUpdate.values
        .groupBy(arrivals => (arrivals.Terminal, SDate(arrivals.Scheduled).toUtcDate))
      val removals: Map[(Terminal, UtcDate), Iterable[Arrival]] = container.toRemove
        .groupBy(arrival => (arrival.Terminal, SDate(arrival.Scheduled).toUtcDate))

      val keys = updates.keys ++ removals.keys
      keys
        .map { terminalDay =>
          val terminalUpdates = updates.getOrElse(terminalDay, List())
          val terminalRemovals = removals.getOrElse(terminalDay, List())
          val diff = ArrivalsDiff(terminalUpdates, terminalRemovals)
          (terminalDay, diff)
        }
        .toMap
  }

  def effectsFromUpdate(partition: (Terminal, UtcDate), updates: FlightUpdates): Future[UpdatedMillis] =
    updateFlights(partition, updates)

  override def shouldSendEffectsToSubscriber: FlightUpdates => Boolean = {
    case _: ArrivalsDiff => true
    case _: SplitsForArrivals => false
  }
}
