package uk.gov.homeoffice.drt.testsystem

import actors.DrtStaticParameters.expireAfterMillis
import actors.daily.RequestAndTerminate
import actors.persistent.arrivals.CiriumLiveArrivalsActor
import actors.routing.FeedArrivalsRouterActor
import actors.{DrtParameters, FlightLookupsLike, ManifestLookupsLike, StreamingJournalLike}
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import drt.server.feeds.Feed
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.acl.AclFeed
import drt.shared.CrunchApi.MillisSinceEpoch
import play.api.Configuration
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AclFeedSource, AirportConfig, FeedSource, ForecastFeedSource, LiveBaseFeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.service.FeedService
import uk.gov.homeoffice.drt.service.ProdFeedService.{getFeedArrivalsLookup, partitionUpdates, partitionUpdatesBase, updateFeedArrivals}
import uk.gov.homeoffice.drt.testsystem.TestActors.{ResetData, TestAclForecastArrivalsActor, TestFeedArrivalsRouterActor, TestPortForecastArrivalsActor, TestPortLiveArrivalsActor, TestTerminalDayFeedArrivalActor}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

case class TestFeedService(journalType: StreamingJournalLike,
                           airportConfig: AirportConfig,
                           now: () => SDateLike,
                           params: DrtParameters,
                           config: Configuration,
                           paxFeedSourceOrder: List[FeedSource],
                           flightLookups: FlightLookupsLike,
                           manifestLookups: ManifestLookupsLike,
                           requestAndTerminateActor: ActorRef,
                           forecastMaxDays: Int,
                          )
                          (implicit val system: ActorSystem, val ec: ExecutionContext, val mat: Materializer, val timeout: Timeout,
                          ) extends FeedService {
  private val nowMillis = () => now().millisSinceEpoch

  def resetFlightsData(source: FeedSource,
                       props: (Int, Int, Int, Terminal, FeedSource, Option[MillisSinceEpoch], () => MillisSinceEpoch, Int) => Props,
                      ): (Terminal, UtcDate) => Future[Any] = (terminal: Terminal, date: UtcDate) => {
    val actor = system.actorOf(props(date.year, date.month, date.day, terminal, source,
      PartialFunction[Any, Option[MillisSinceEpoch]] {
        case AclFeedSource => None
        case LiveFeedSource => Some(nowMillis())
        case LiveBaseFeedSource => Some(nowMillis())
        case ForecastFeedSource => Some(nowMillis())
      }, nowMillis, expireAfterMillis
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  override val forecastBaseFeedArrivalsActor: ActorRef = system.actorOf(Props(new TestFeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(AclFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = true), nowMillis, requestAndTerminateActor),
    updateFeedArrivals(AclFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = true), nowMillis, requestAndTerminateActor),
    partitionUpdatesBase(airportConfig.terminals, now, forecastMaxDays),
    resetFlightsData(AclFeedSource)
  )), name = "forecast-base-arrivals-actor")
  override val forecastFeedArrivalsActor: ActorRef = system.actorOf(Props(new TestFeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = false), nowMillis, requestAndTerminateActor),
    updateFeedArrivals(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = false), nowMillis, requestAndTerminateActor),
    partitionUpdates,
  )), name = "forecast-arrivals-actor")
  override val liveFeedArrivalsActor: ActorRef = system.actorOf(Props(new TestFeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(LiveFeedSource, TerminalDayFeedArrivalActor.live, nowMillis, requestAndTerminateActor),
    updateFeedArrivals(LiveFeedSource, TerminalDayFeedArrivalActor.live, nowMillis, requestAndTerminateActor),
    partitionUpdates,
  )), name = "live-arrivals-actor")
  override val liveBaseFeedArrivalsActor: ActorRef = system.actorOf(Props(new TestFeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(LiveBaseFeedSource, TerminalDayFeedArrivalActor.live, nowMillis, requestAndTerminateActor),
    updateFeedArrivals(LiveBaseFeedSource, TerminalDayFeedArrivalActor.live, nowMillis, requestAndTerminateActor),
    partitionUpdates,
  )), name = "live-base-arrivals-actor")

  override val maybeAclFeed: Option[AclFeed] = None

  override def baseArrivalsSource(maybeAclFeed: Option[AclFeed]): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def forecastArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def liveBaseArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp
}
