package actors.routing

import actors.PartitionedPortStateActor.{DateRangeMillisLike, PointInTimeQuery}
import actors.daily.RequestAndTerminate
import actors.routing.FeedArrivalsRouterActor.FeedArrivals
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
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{DateRange, SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object FeedArrivalsRouterActor {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  case class FeedArrivals(arrivals: Seq[FeedArrival]) extends FlightUpdates

  sealed trait query

  case class GetStateForDateRange(start: UtcDate, end: UtcDate) extends query with DateRangeMillisLike {
    override val from: MillisSinceEpoch = SDate(start).millisSinceEpoch
    override val to: MillisSinceEpoch = SDate(end).millisSinceEpoch
  }

  case class GetStateForDateRangeAndTerminal(start: UtcDate, end: UtcDate, terminal: Terminal) extends query with DateRangeMillisLike {
    override val from: MillisSinceEpoch = SDate(start).millisSinceEpoch
    override val to: MillisSinceEpoch = SDate(end).millisSinceEpoch
  }

  def updateArrivals(requestAndTerminateActor: ActorRef,
                     props: (UtcDate, Terminal) => Props,
                   )
                    (implicit system: ActorSystem, timeout: Timeout): ((Terminals.Terminal, UtcDate), Seq[FeedArrival]) => Future[Boolean] =
    (partition: (Terminal, UtcDate), arrivals: Seq[FeedArrival]) => {
      val (terminal, date) = partition
      val actor = system.actorOf(props(date, terminal))
      requestAndTerminateActor.ask(RequestAndTerminate(actor, arrivals)).mapTo[Boolean]
    }

  def feedArrivalsDayLookup(now: () => Long,
                            requestAndTerminateActor: ActorRef,
                            props: (UtcDate, Terminal, Option[MillisSinceEpoch], () => Long) => Props,
                           )
                           (implicit
                            system: ActorSystem,
                            timeout: Timeout,
                            ec: ExecutionContext,
                           ): Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[Seq[FeedArrival]] =
    (maybePit: Option[MillisSinceEpoch]) => (date: UtcDate) => (terminal: Terminal) => {
      val actor = system.actorOf(props(date, terminal, maybePit, now))
      requestAndTerminateActor
        .ask(RequestAndTerminate(actor, TerminalDayFeedArrivalActor.GetState))
        .mapTo[Map[UniqueArrival, FeedArrival]]
        .map(_.values.toSeq)
    }

  def multiTerminalArrivalsByDaySource(flightsLookupByDayAndTerminal: Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[Seq[FeedArrival]])
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
                              updateArrivals: ((Terminals.Terminal, UtcDate), Seq[FeedArrival]) => Future[Boolean],
                              override val partitionUpdates: PartialFunction[FeedArrivals, Map[(Terminal, UtcDate), FeedArrivals]],
                             ) extends RouterActorLikeWithSubscriber[FeedArrivals, (Terminal, UtcDate), Long] {
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
    FeedArrivalsRouterActor.multiTerminalArrivalsByDaySource(arrivalsByDayLookup)

  override def updatePartition(partition: (Terminal, UtcDate), updates: FeedArrivals): Future[Set[Long]] = {
    log.info(s"FeedArrivalsRouterActor updating $partition")
    updateArrivals(partition, updates.arrivals).map {
      case true =>
        Set(SDate(partition._2).millisSinceEpoch)
      case false =>
        Set.empty
    }
  }

  override def shouldSendEffectsToSubscriber: FeedArrivals => Boolean = _ => true
}
