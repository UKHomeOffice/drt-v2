package actors.routing

import actors.DateRange
import actors.PartitionedPortStateActor._
import actors.daily.RequestAndTerminateActor
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.routing.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import controllers.model.RedListCounts
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi._
import drt.shared._
import services.SourceUtils
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, FlightsWithSplits, SplitsForArrivals, UniqueArrival}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import scala.collection.immutable.NumericRange
import scala.concurrent.{ExecutionContext, Future}


object FlightsRouterActor {

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

  def multiTerminalFlightsByDaySource(flightsLookupByDayAndTerminal: FlightsLookup)
                                     (start: SDateLike,
                                      end: SDateLike,
                                      terminals: Iterable[Terminal],
                                      maybePit: Option[MillisSinceEpoch],
                                      paxFeedSourceOrder: List[FeedSource],
                                     )
                                     (implicit ec: ExecutionContext): Source[(UtcDate, FlightsWithSplits), NotUsed] = {
    val dates: Seq[UtcDate] = DateRange.utcDateRangeWithBuffer(2, 1)(start, end)

    val reduceAndSort = SourceUtils.reduceFutureIterables(terminals, reduceAndSortFlightsWithSplits)
    val flightsLookupByDay = flightsLookupByDayAndTerminal(maybePit)

    Source(dates.toList)
      .mapAsync(1)(d => reduceAndSort(flightsLookupByDay(d)).map(f => (d, f)))
      .map { case (d, flights) => (d, flights.scheduledOrPcpWindow(start, end, paxFeedSourceOrder)) }
      .filter { case (_, flights) => flights.nonEmpty }
  }

  private val reduceAndSortFlightsWithSplits: Iterable[FlightsWithSplits] => FlightsWithSplits = (allFlightsWithSplits: Iterable[FlightsWithSplits]) => {
    val reducedFlightsWithSplits = allFlightsWithSplits
      .reduce(_ ++ _)
      .flights.values.toList.sortBy { fws =>
      val arrival = fws.apiFlight
      (arrival.PcpTime, arrival.VoyageNumber.numeric, arrival.Origin.iata)
    }
    FlightsWithSplits(reducedFlightsWithSplits)
  }

  def runAndCombine(eventualSource: Future[Source[(UtcDate, FlightsWithSplits), NotUsed]])
                   (implicit mat: Materializer, ec: ExecutionContext): Future[FlightsWithSplits] = eventualSource
    .flatMap(source => source
      .log(getClass.getName)
      .runWith(Sink.fold(FlightsWithSplits.empty)(_ ++ _._2))
    )
}

class FlightsRouterActor(allTerminals: Iterable[Terminal],
                         flightsByDayLookup: FlightsLookup,
                         updateFlights: FlightsUpdate,
                         paxFeedSourceOrder: List[FeedSource],
                        ) extends RouterActorLikeWithSubscriber[FlightUpdates, (Terminal, UtcDate)] {
  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()), "flights-router-actor-kill-actor")

  override def receiveQueries: Receive = {
    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      sender() ! flightsLookupService(SDate(startMillis), SDate(endMillis), allTerminals, Option(pit), paxFeedSourceOrder)

    case PointInTimeQuery(pit, GetFlightsForTerminals(startMillis, endMillis, terminals)) =>
      sender() ! flightsLookupService(SDate(startMillis), SDate(endMillis), terminals, Option(pit), paxFeedSourceOrder)

    case PointInTimeQuery(pit, GetFlights(startMillis, endMillis)) =>
      self.forward(PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)))

    case PointInTimeQuery(pit, request: DateRangeLike with TerminalRequest) =>
      sender() ! flightsLookupService(SDate(request.from), SDate(request.to), Seq(request.terminal), Option(pit), paxFeedSourceOrder)

    case GetFlightsForTerminals(startMillis, endMillis, terminals) =>
      sender() ! flightsLookupService(SDate(startMillis), SDate(endMillis), terminals, None, paxFeedSourceOrder)

    case GetStateForDateRange(startMillis, endMillis) =>
      sender() ! flightsLookupService(SDate(startMillis), SDate(endMillis), allTerminals, None, paxFeedSourceOrder)

    case GetFlights(startMillis, endMillis) =>
      self.forward(GetStateForDateRange(startMillis, endMillis))

    case request: DateRangeLike with TerminalRequest =>
      sender() ! flightsLookupService(SDate(request.from), SDate(request.to), Seq(request.terminal), None, paxFeedSourceOrder)
  }

  private val flightsLookupService: (SDateLike, SDateLike, Iterable[Terminal], Option[MillisSinceEpoch], List[FeedSource]) => Source[(UtcDate, FlightsWithSplits), NotUsed] =
    FlightsRouterActor.multiTerminalFlightsByDaySource(flightsByDayLookup)

  override def partitionUpdates: PartialFunction[FlightUpdates, Map[(Terminal, UtcDate), FlightUpdates]] = {
    case container: RedListCounts =>
      container.passengers
        .groupBy {
          case RedListPassengers(_, _, scheduled, _) => scheduled.toUtcDate
        }
        .flatMap {
          case (sch, counts) =>
            allTerminals.map(t => ((t, sch), RedListCounts(counts)))
        }

    case container: SplitsForArrivals =>
      container.splits
        .groupBy {
          case (uniqueArrival, _) => (uniqueArrival.terminal, SDate(uniqueArrival.scheduled).toUtcDate)
        }
        .map {
          case (terminalDay, allSplits) => (terminalDay, SplitsForArrivals(allSplits))
        }

    case container: PaxForArrivals =>
      container.pax
        .groupBy {
          case (uniqueArrival, _) => (uniqueArrival.terminal, SDate(uniqueArrival.scheduled).toUtcDate)
        }
        .map {
          case (terminalDay, allPax) => (terminalDay, PaxForArrivals(allPax))
        }

    case container: ArrivalsDiff =>
      val updates: Map[(Terminal, UtcDate), Iterable[Arrival]] = container.toUpdate.values
        .groupBy(arrivals => (arrivals.Terminal, SDate(arrivals.Scheduled).toUtcDate))
      val removals: Map[(Terminal, UtcDate), Iterable[UniqueArrival]] = container.toRemove
        .groupBy(arrival => (arrival.terminal, SDate(arrival.scheduled).toUtcDate))

      val keys = updates.keys ++ removals.keys
      keys
        .map { terminalDay =>
          val terminalUpdates = updates.getOrElse(terminalDay, List())
          val terminalRemovals = removals.getOrElse(terminalDay, List())
          val diff = ArrivalsDiff(terminalUpdates, terminalRemovals)
          (terminalDay, diff)
        }
        .toMap

    case RemoveSplitsForDateRange(startMillis, endMillis) =>
      val dates = (startMillis to endMillis by MilliTimes.oneHourMillis)
        .map(millis => SDate(millis).toUtcDate)
        .toSet
      allTerminals.flatMap(t => dates.map(d => ((t, d), RemoveSplits))).toMap
  }

  def updatePartition(partition: (Terminal, UtcDate), updates: FlightUpdates): Future[UpdatedMillis] =
    updateFlights(partition, updates)

  override def shouldSendEffectsToSubscriber: FlightUpdates => Boolean = {
    case _: ArrivalsDiff => true
    case _: SplitsForArrivals => false
  }
}
