package actors.routing

import actors.DateRange
import actors.PartitionedPortStateActor.{DateRangeMillisLike, PointInTimeQuery}
import actors.daily.RequestAndTerminate
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import services.SourceUtils
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor.FeedArrivalsDiff
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object FeedArrivalsRouterActor {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  sealed trait query

  case class GetStateForDateRange(start: UtcDate, end: UtcDate) extends query with DateRangeMillisLike {
    override val from: MillisSinceEpoch = SDate(start).millisSinceEpoch
    override val to: MillisSinceEpoch = SDate(end).millisSinceEpoch
  }

  case class GetStateForDateRangeAndTerminal(start: UtcDate, end: UtcDate, terminal: Terminal) extends query with DateRangeMillisLike {
    override val from: MillisSinceEpoch = SDate(start).millisSinceEpoch
    override val to: MillisSinceEpoch = SDate(end).millisSinceEpoch
  }

  def updateFlights(requestAndTerminateActor: ActorRef,
                    props: (UtcDate, Terminal) => Props,
                   )
                   (implicit system: ActorSystem, timeout: Timeout): ((Terminals.Terminal, UtcDate), FlightUpdates) => Future[Boolean] =
    (partition: (Terminal, UtcDate), diff: FlightUpdates) => {
      val (terminal, date) = partition
      val actor = system.actorOf(props(date, terminal))
      requestAndTerminateActor.ask(RequestAndTerminate(actor, diff)).mapTo[Boolean]
    }

  def feedArrivalsDayLookup(now: () => Long,
                            requestAndTerminateActor: ActorRef,
                            props: (UtcDate, Terminal, Option[MillisSinceEpoch], () => Long) => Props,
                           )
                           (implicit
                            system: ActorSystem,
                            timeout: Timeout,
                           ): Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[Seq[FeedArrival]] =
    (maybePit: Option[MillisSinceEpoch]) => (date: UtcDate) => (terminal: Terminal) => {
      val actor = system.actorOf(props(date, terminal, maybePit, now))
      requestAndTerminateActor
        .ask(RequestAndTerminate(actor, TerminalDayFeedArrivalActor.GetState))
        .mapTo[Seq[FeedArrival]]
    }

  def multiTerminalFlightsByDaySource(flightsLookupByDayAndTerminal: Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[Seq[FeedArrival]])
                                     (start: UtcDate,
                                      end: UtcDate,
                                      terminals: Iterable[Terminal],
                                      maybePit: Option[MillisSinceEpoch],
                                     )
                                     (implicit ec: ExecutionContext): Source[(UtcDate, Seq[FeedArrival]), NotUsed] = {
    val dates: Seq[UtcDate] = DateRange(start, end)

    val reduceAndSort = SourceUtils.reduceFutureIterables(terminals, (s: Iterable[Seq[FeedArrival]]) => s.reduce(_ ++ _))
    val flightsLookupByDay = flightsLookupByDayAndTerminal(maybePit)

    Source(dates.toList)
      .mapAsync(1)(d => reduceAndSort(flightsLookupByDay(d)).map(f => (d, f)))
      .recover {
        case e: Throwable =>
          log.error(s"Error in multiTerminalFlightsByDaySource: ${e.getMessage}")
          (dates.toList.head, Seq.empty)
      }
      .filter { case (_, flights) => flights.nonEmpty }
  }
}

class FeedArrivalsRouterActor(allTerminals: Iterable[Terminal],
                              arrivalsByDayLookup: Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[Seq[FeedArrival]],
                              updateArrivals: ((Terminals.Terminal, UtcDate), FlightUpdates) => Future[Boolean],
                             ) extends RouterActorLikeWithSubscriber[FlightUpdates, (Terminal, UtcDate), Long] {
  override def receiveQueries: Receive = {
    case PointInTimeQuery(pit, FeedArrivalsRouterActor.GetStateForDateRange(start, end)) =>
      sender() ! flightsLookupService(start, end, allTerminals, Option(pit))

    case PointInTimeQuery(pit, FeedArrivalsRouterActor.GetStateForDateRangeAndTerminal(start, end, terminal)) =>
      sender() ! flightsLookupService(start, end, Seq(terminal), Option(pit))

    case FeedArrivalsRouterActor.GetStateForDateRange(start, end) =>
      sender() ! flightsLookupService(start, end, allTerminals, None)

    case FeedArrivalsRouterActor.GetStateForDateRangeAndTerminal(start, end, terminal) =>
      sender() ! flightsLookupService(start, end, Seq(terminal), None)
  }

  private val flightsLookupService: (UtcDate, UtcDate, Iterable[Terminal], Option[MillisSinceEpoch]) => Source[(UtcDate, Seq[FeedArrival]), NotUsed] =
    FeedArrivalsRouterActor.multiTerminalFlightsByDaySource(arrivalsByDayLookup)

  override def partitionUpdates: PartialFunction[FlightUpdates, Map[(Terminal, UtcDate), FlightUpdates]] = {
    case container: FeedArrivalsDiff[FeedArrival] =>
      val updates: Map[(Terminal, UtcDate), Iterable[FeedArrival]] = container.updates
        .groupBy(arrivals => (arrivals.terminal, SDate(arrivals.scheduled).toUtcDate))
      val removals: Map[(Terminal, UtcDate), Iterable[UniqueArrival]] = container.removals
        .groupBy(arrival => (arrival.terminal, SDate(arrival.scheduled).toUtcDate))

      val keys = updates.keys ++ removals.keys
      keys
        .map { terminalDay =>
          val terminalUpdates = updates.getOrElse(terminalDay, List())
          val terminalRemovals = removals.getOrElse(terminalDay, List())
          val diff = FeedArrivalsDiff(terminalUpdates, terminalRemovals)
          (terminalDay, diff)
        }
        .toMap
  }

  def updatePartition(partition: (Terminal, UtcDate), updates: FlightUpdates): Future[Set[Long]] =
    updateArrivals(partition, updates).map {
      case true => Set(SDate(partition._2).millisSinceEpoch)
    }

  override def shouldSendEffectsToSubscriber: FlightUpdates => Boolean = _ => true
}
