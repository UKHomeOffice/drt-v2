package uk.gov.homeoffice.drt.testsystem

import actors.DrtStaticParameters.expireAfterMillis
import actors.persistent.arrivals.CiriumLiveArrivalsActor
import actors.{DrtParameters, FlightLookupsLike, ManifestLookupsLike, StreamingJournalLike}
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.stream.Materializer
import akka.util.Timeout
import drt.server.feeds.Feed
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.acl.AclFeed
import play.api.Configuration
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.service.FeedService
import uk.gov.homeoffice.drt.testsystem.TestActors.{TestAclForecastArrivalsActor, TestPortForecastArrivalsActor, TestPortLiveArrivalsActor}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext

case class TestFeedService(journalType: StreamingJournalLike,
                           airportConfig: AirportConfig,
                           now: () => SDateLike,
                           params: DrtParameters,
                           config: Configuration,
                           paxFeedSourceOrder: List[FeedSource],
                           flightLookups: FlightLookupsLike,
                           manifestLookups: ManifestLookupsLike,
                          )
                          (implicit val system: ActorSystem, val ec: ExecutionContext, val mat: Materializer, val timeout: Timeout,
                          ) extends FeedService {

  override val forecastBaseFeedArrivalsActor: ActorRef =
    system.actorOf(Props(new TestAclForecastArrivalsActor(now, expireAfterMillis)), name = "base-arrivals-actor")
  override val forecastFeedArrivalsActor: ActorRef =
    system.actorOf(Props(new TestPortForecastArrivalsActor(now, expireAfterMillis)), name = "forecast-arrivals-actor")
  override val liveFeedArrivalsActor: ActorRef =
    system.actorOf(Props(new TestPortLiveArrivalsActor(now, expireAfterMillis)), name = "live-arrivals-actor")
  override val liveBaseFeedArrivalsActor: ActorRef =
    system.actorOf(Props(new CiriumLiveArrivalsActor(now, expireAfterMillis)), name = "live-base-arrivals-actor")

  override val maybeAclFeed: Option[AclFeed] = None

  override def baseArrivalsSource(maybeAclFeed: Option[AclFeed]): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def forecastArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def liveBaseArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp

  override def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = arrivalsNoOp
}
