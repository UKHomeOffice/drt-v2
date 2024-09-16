package uk.gov.homeoffice.drt.testsystem

import actors.DrtStaticParameters.expireAfterMillis
import actors.daily.RequestAndTerminate
import actors.routing.FeedArrivalsRouterActor.FeedArrivals
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
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.service.FeedService
import uk.gov.homeoffice.drt.service.ProdFeedService.{getFeedArrivalsLookup, partitionUpdates, partitionUpdatesBase, updateFeedArrivals}
import uk.gov.homeoffice.drt.testsystem.TestActors.{ResetData, TestFeedArrivalsRouterActor}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

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

  override val legacyFeedArrivalsBeforeDate: SDateLike = SDate("2024-04-03")

  def resetFlightsData(source: FeedSource,
                       props: (Int, Int, Int, Terminal, FeedSource, Option[MillisSinceEpoch], () => MillisSinceEpoch, Int) => Props,
                      ): (Terminal, UtcDate) => Future[Any] = (terminal: Terminal, date: UtcDate) => {
    val actor = system.actorOf(props(date.year, date.month, date.day, terminal, source, None, nowMillis, expireAfterMillis))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  private def feedArrivalsRouter(source: FeedSource,
                                 partitionUpdates: PartialFunction[FeedArrivals, Map[(Terminal, UtcDate), FeedArrivals]],
                                 name: String): ActorRef =
    system.actorOf(Props(new TestFeedArrivalsRouterActor(
      airportConfig.terminals,
      getFeedArrivalsLookup(source, TerminalDayFeedArrivalActor.props, nowMillis, requestAndTerminateActor),
      updateFeedArrivals(source, TerminalDayFeedArrivalActor.props, nowMillis, requestAndTerminateActor),
      processingRequest =
        (terminal, date) => TerminalUpdateRequest(terminal, SDate(date).toLocalDate, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch),
      partitionUpdates,
      resetFlightsData(AclFeedSource, TerminalDayFeedArrivalActor.props)
    )), name = name)

  override val forecastBaseFeedArrivalsActor: ActorRef = feedArrivalsRouter(AclFeedSource,
    partitionUpdatesBase(airportConfig.terminals, now, forecastMaxDays),
    "forecast-base-arrivals-actor")
  override val forecastFeedArrivalsActor: ActorRef = feedArrivalsRouter(ForecastFeedSource, partitionUpdates, "forecast-arrivals-actor")
  override val liveBaseFeedArrivalsActor: ActorRef = feedArrivalsRouter(LiveBaseFeedSource, partitionUpdates, "live-base-arrivals-actor")
  override val liveFeedArrivalsActor: ActorRef = feedArrivalsRouter(LiveFeedSource, partitionUpdates, "live-arrivals-actor")

  override val maybeAclFeed: Option[AclFeed] = None

  override def baseArrivalsSource(maybeAclFeed: Option[AclFeed]): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def forecastArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def liveBaseArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp
}
