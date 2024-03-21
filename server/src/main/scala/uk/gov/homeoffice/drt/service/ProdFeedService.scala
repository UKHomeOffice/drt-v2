package uk.gov.homeoffice.drt.service

import actors.PartitionedPortStateActor.{GetFlights, GetStateForDateRange, PointInTimeQuery}
import actors._
import actors.persistent.ManifestRouterActor
import actors.persistent.arrivals.{AclForecastArrivalsActor, CiriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing.GetFeedStatuses
import actors.routing.FeedArrivalsRouterActor
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown, Props, Scheduler, typed}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFlightMarshallers}
import drt.chroma.{ChromaFeedType, ChromaLive}
import drt.http.ProdSendAndReceive
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.acl.AclFeed
import drt.server.feeds.bhx.{BHXClient, BHXFeed}
import drt.server.feeds.chroma.ChromaLiveFeed
import drt.server.feeds.cirium.CiriumFeed
import drt.server.feeds.common.{ManualUploadArrivalFeed, ProdHttpClient}
import drt.server.feeds.edi.EdiFeed
import drt.server.feeds.gla.GlaFeed
import drt.server.feeds.lcy.{LCYClient, LCYFeed}
import drt.server.feeds.legacy.bhx.BHXForecastFeedLegacy
import drt.server.feeds.lgw.{LGWAzureClient, LGWFeed, LgwForecastFeed}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.server.feeds.lhr.sftp.LhrSftpLiveContentProvider
import drt.server.feeds.ltn.{LtnFeedRequester, LtnLiveFeed}
import drt.server.feeds.mag.{MagFeed, ProdFeedRequester}
import drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess, Feed, FeedPoller}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import services.arrivals.MergeArrivals.FeedArrivalSet
import services.{Retry, RetryDelays, StreamSupervision}
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.prediction.ModelPersistence
import uk.gov.homeoffice.drt.prediction.persistence.Flight
import uk.gov.homeoffice.drt.time._

import javax.inject.Singleton
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ProdFeedService {
  def arrivalFeedProvidersInOrder(feedActorsWithPrimary: Seq[(Boolean, Option[FiniteDuration], ActorRef)],
                                 )
                                 (implicit timeout: Timeout, ec: ExecutionContext): Seq[DateLike => Future[FeedArrivalSet]] =
    feedActorsWithPrimary
      .map {
        case (isPrimary, maybeFuzzyThreshold, actor) =>
          val arrivalsForDate = (date: DateLike) => {
            val start = SDate(date)
            val end = start.addDays(1).addMinutes(-1)
            actor
              .ask(GetFlights(start.millisSinceEpoch, end.millisSinceEpoch))
              .mapTo[Map[UniqueArrival, Arrival]]
              .map(f => FeedArrivalSet(isPrimary, maybeFuzzyThreshold, f))
          }
          arrivalsForDate
      }

}

trait FeedService {
  protected val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout

  val journalType: StreamingJournalLike
  val airportConfig: AirportConfig
  val now: () => SDateLike
  val params: DrtParameters
  val config: Configuration
  val paxFeedSourceOrder: List[FeedSource]
  val flightLookups: FlightLookupsLike
  val manifestLookups: ManifestLookupsLike

  val forecastBaseFeedArrivalsActor: ActorRef
  val forecastFeedArrivalsActor: ActorRef
  val liveFeedArrivalsActor: ActorRef
  val liveBaseFeedArrivalsActor: ActorRef

  val maybeAclFeed: Option[AclFeed]

  val fcstBaseFeedPollingActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-forecast-base")
  val fcstFeedPollingActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-forecast")
  val liveBaseFeedPollingActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-live-base")
  val liveFeedPollingActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-live")

  private val ciriumIsPrimary: Boolean = !airportConfig.feedSources.contains(LiveFeedSource)

  lazy val activeFeedActorsWithPrimary: Seq[(Boolean, Option[FiniteDuration], ActorRef)] = Seq(
    AclFeedSource -> (true, None, forecastBaseFeedArrivalsActor),
    ForecastFeedSource -> (false, None, forecastFeedArrivalsActor),
    LiveBaseFeedSource -> (ciriumIsPrimary, Option(5.minutes), liveBaseFeedArrivalsActor),
    LiveFeedSource -> (true, None, liveFeedArrivalsActor)
  )
    .collect {
      case (fs, (isPrimary, maybeFuzzyThreshold, actor)) if airportConfig.feedSources.contains(fs) => (isPrimary, maybeFuzzyThreshold, actor)
    }

  lazy val feedActors: Map[FeedSource, ActorRef] = Map(
    AclFeedSource -> forecastBaseFeedArrivalsActor,
    ForecastFeedSource -> forecastFeedArrivalsActor,
    LiveBaseFeedSource -> liveBaseFeedArrivalsActor,
    LiveFeedSource -> liveFeedArrivalsActor,
  )

  val flightModelPersistence: ModelPersistence = Flight()

  private val liveArrivalsFeedStatusActor: ActorRef =
    system.actorOf(PortLiveArrivalsActor.streamingUpdatesProps(journalType), name = "live-arrivals-feed-status")
  private val liveBaseArrivalsFeedStatusActor: ActorRef =
    system.actorOf(CiriumLiveArrivalsActor.streamingUpdatesProps(journalType), name = "live-base-arrivals-feed-status")
  private val forecastArrivalsFeedStatusActor: ActorRef =
    system.actorOf(PortForecastArrivalsActor.streamingUpdatesProps(journalType), name = "forecast-arrivals-feed-status")
  private val forecastBaseArrivalsFeedStatusActor: ActorRef =
    system.actorOf(AclForecastArrivalsActor.streamingUpdatesProps(journalType), name = "forecast-base-arrivals-feed-status")
  private val manifestsFeedStatusActor: ActorRef =
    system.actorOf(ManifestRouterActor.streamingUpdatesProps(journalType), name = "manifests-feed-status")

  private val feedStatusActors: Map[FeedSource, ActorRef] = Map(
    LiveFeedSource -> liveArrivalsFeedStatusActor,
    LiveBaseFeedSource -> liveBaseArrivalsFeedStatusActor,
    ForecastFeedSource -> forecastArrivalsFeedStatusActor,
    AclFeedSource -> forecastBaseArrivalsFeedStatusActor,
    ApiFeedSource -> manifestsFeedStatusActor,
  )

  private def flightValuesForDate[T](date: LocalDate,
                                     maybeAtTime: Option[SDateLike],
                                     flightIsRelevant: Arrival => Boolean,
                                     extractValue: Iterable[Arrival] => T,
                                    ): Future[Map[Terminal, T]] = {
    val start = SDate(date)
    val end = start.addDays(1).addMinutes(-1)
    val rangeRequest = GetStateForDateRange(start.millisSinceEpoch, end.millisSinceEpoch)
    val request = maybeAtTime match {
      case Some(atTime) => PointInTimeQuery(atTime.millisSinceEpoch, rangeRequest)
      case None => rangeRequest
    }

    flightLookups.flightsRouterActor.ask(request)
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .flatMap { source =>
        source.mapConcat {
          case (_, flights) =>
            flights.flights
              .filter { case (_, ApiFlightWithSplits(apiFlight, _, _)) =>
                val nonCtaOrDom = !apiFlight.Origin.isDomesticOrCta
                nonCtaOrDom && flightIsRelevant(apiFlight)
              }
              .values
              .groupBy(fws => fws.apiFlight.Terminal)
              .map {
                case (terminal, flights) =>
                  (terminal, extractValue(flights.map(_.apiFlight)))
              }
        }.runWith(Sink.seq)
      }
      .map(_.toMap)
  }

  val forecastArrivals: (LocalDate, SDateLike) => Future[Map[Terminal, Seq[Arrival]]] = (date: LocalDate, atTime: SDateLike) =>
    flightValuesForDate(
      date,
      Option(atTime),
      arrival => SDate(arrival.Scheduled).toLocalDate == date,
      arrivals => arrivals.toSeq
    )

  val actualArrivals: LocalDate => Future[Map[Terminal, Seq[Arrival]]] = (date: LocalDate) =>
    flightValuesForDate(
      date,
      None,
      arrival => SDate(arrival.Scheduled).toLocalDate == date,
      arrivals => arrivals.toSeq
    )

  val forecastPaxNos: (LocalDate, SDateLike) => Future[Map[Terminal, Double]] = (date: LocalDate, atTime: SDateLike) =>
    flightValuesForDate(
      date,
      Option(atTime),
      arrival => SDate(arrival.Scheduled).toLocalDate == date,
      arrivals => arrivals.map(arrival => arrival.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)).sum
    )

  val actualPaxNos: LocalDate => Future[Map[Terminal, Double]] = (date: LocalDate) =>
    flightValuesForDate(
      date,
      None,
      arrival => SDate(arrival.Scheduled).toLocalDate == date,
      arrivals => arrivals.map(arrival => arrival.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)).sum
    )

  val arrivalsImportActor: ActorRef = system.actorOf(Props(new ArrivalsImportActor()), name = "arrivals-import-actor")

  def isValidFeedSource(fs: FeedSource): Boolean = airportConfig.feedSources.contains(fs)

  lazy val feedActorsForPort: Map[FeedSource, ActorRef] = feedStatusActors.filter {
    case (feedSource: FeedSource, _) => isValidFeedSource(feedSource)
  }

  def queryActorWithRetry[A](actor: ActorRef, toAsk: Any): Future[Option[A]] = {
    val future = actor.ask(toAsk)(new Timeout(2.minutes)).map {
      case Some(state: A) if state.isInstanceOf[A] => Option(state)
      case state: A if !state.isInstanceOf[Option[A]] => Option(state)
      case _ => None
    }

    implicit val scheduler: Scheduler = system.scheduler
    Retry.retry(future, RetryDelays.fibonacci, 3, 5.seconds)
  }

  def getFeedStatus: Future[Seq[FeedSourceStatuses]] = Source(feedActorsForPort)
    .mapAsync(1) {
      case (_, actor) => queryActorWithRetry[FeedSourceStatuses](actor, GetFeedStatuses)
    }
    .collect { case Some(fs) => fs }
    .withAttributes(StreamSupervision.resumeStrategyWithLog("getFeedStatus"))
    .runWith(Sink.seq)

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdown-feeds") { () =>
    log.info("Shutting down feed polling")

    fcstBaseFeedPollingActor ! FeedPoller.Shutdown
    fcstFeedPollingActor ! FeedPoller.Shutdown
    liveBaseFeedPollingActor ! FeedPoller.Shutdown
    liveFeedPollingActor ! FeedPoller.Shutdown

    Future.successful(Done)
  }

  def arrivalsNoOp: Feed[typed.ActorRef[FeedTick]] =
    Feed(Feed.actorRefSource
      .map { _ =>
        system.log.info(s"No op arrivals feed")
        ArrivalsFeedSuccess(Seq(), SDate.now())
      }, 100.days, 100.days)

  def baseArrivalsSource(maybeAclFeed: Option[AclFeed]): Feed[typed.ActorRef[FeedTick]]

  def forecastArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]]

  def liveBaseArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]]

  def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]]
}

@Singleton
case class ProdFeedService(journalType: StreamingJournalLike,
                           airportConfig: AirportConfig,
                           now: () => SDateLike,
                           params: DrtParameters,
                           config: Configuration,
                           paxFeedSourceOrder: List[FeedSource],
                           flightLookups: FlightLookupsLike,
                           manifestLookups: ManifestLookupsLike,
                           requestAndTerminateActor: ActorRef,
                          )
                          (implicit
                           val system: ActorSystem,
                           val ec: ExecutionContext,
                           val timeout: Timeout,
                          ) extends FeedService {
  private val nowMillis = () => now().millisSinceEpoch

  private def getFeedArrivalsLookup(source: FeedSource,
                                    props: (Int, Int, Int, Terminal, FeedSource, Option[MillisSinceEpoch], () => MillisSinceEpoch, Int) => Props,
                                   ): Option[MillisSinceEpoch] => UtcDate => Terminal => Future[Seq[FeedArrival]] = {
    FeedArrivalsRouterActor.feedArrivalsDayLookup(
      now = nowMillis,
      requestAndTerminateActor = requestAndTerminateActor,
      props = (d, t, mp, n) => props(d.year, d.month, d.day, t, source, mp, n, 250)
    )
  }

  private def updateForecastBaseArrivals(source: FeedSource,
                                         props: (Int, Int, Int, Terminal, FeedSource, Option[MillisSinceEpoch], () => MillisSinceEpoch, Int) => Props,
                                        ): ((Terminal, UtcDate), FlightUpdates) => Future[Boolean] =
    FeedArrivalsRouterActor.updateFlights(
      requestAndTerminateActor,
      (d, t) => props(d.year, d.month, d.day, t, source, None, nowMillis, 250),
    )

  override val forecastBaseFeedArrivalsActor: ActorRef = system.actorOf(Props(new FeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(AclFeedSource, TerminalDayFeedArrivalActor.forecast),
    updateForecastBaseArrivals(AclFeedSource, TerminalDayFeedArrivalActor.forecast),
  )), name = "forecast-base-arrivals-actor")
  override val forecastFeedArrivalsActor: ActorRef = system.actorOf(Props(new FeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast),
    updateForecastBaseArrivals(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast),
  )), name = "forecast-arrivals-actor")
  override val liveFeedArrivalsActor: ActorRef = system.actorOf(Props(new FeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(LiveFeedSource, TerminalDayFeedArrivalActor.live),
    updateForecastBaseArrivals(LiveFeedSource, TerminalDayFeedArrivalActor.live),
  )), name = "live-arrivals-actor")
  override val liveBaseFeedArrivalsActor: ActorRef = system.actorOf(Props(new FeedArrivalsRouterActor(
    airportConfig.terminals,
    getFeedArrivalsLookup(LiveBaseFeedSource, TerminalDayFeedArrivalActor.live),
    updateForecastBaseArrivals(LiveBaseFeedSource, TerminalDayFeedArrivalActor.live),
  )), name = "live-base-arrivals-actor")

  override val maybeAclFeed: Option[AclFeed] =
    if (params.aclDisabled) None
    else
      for {
        host <- params.aclHost
        username <- params.aclUsername
        keyPath <- params.aclKeyPath
      } yield AclFeed(host, username, keyPath, airportConfig.portCode, AclFeed.aclToPortMapping(airportConfig.portCode))

  private def azinqConfig: (String, String, String, String) = {
    val url = config.get[String]("feeds.azinq.url")
    val username = config.get[String]("feeds.azinq.username")
    val password = config.get[String]("feeds.azinq.password")
    val token = config.get[String]("feeds.azinq.token")
    (url, username, password, token)
  }

  private def createLiveChromaFlightFeed(feedType: ChromaFeedType): ChromaLiveFeed = {
    ChromaLiveFeed(new ChromaFetcher[ChromaLiveFlight](feedType, ChromaFlightMarshallers.live) with ProdSendAndReceive)
  }

  private def createArrivalFeed(source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    implicit val timeout: Timeout = new Timeout(10.seconds)
    val arrivalFeed = ManualUploadArrivalFeed(arrivalsImportActor)
    source.mapAsync(1)(_ => arrivalFeed.requestFeed)
  }

  override def forecastArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] =
    portCode match {
      case PortCode("LGW") =>
        val interval = system.settings.config.getString("feeds.lgw.forecast.interval-minutes").toInt.minutes
        val initialDelay = system.settings.config.getString("feeds.lgw.forecast.initial-delay-seconds").toInt.seconds
        Feed(LgwForecastFeed(), initialDelay, interval)
      case PortCode("LHR") | PortCode("STN") =>
        Feed(createArrivalFeed(Feed.actorRefSource), 5.seconds, 5.seconds)
      case PortCode("BHX") =>
        Feed(BHXForecastFeedLegacy(params.maybeBhxSoapEndPointUrl.getOrElse(throw new Exception("Missing BHX feed URL")), Feed.actorRefSource), 5.seconds, 30.seconds)
      case _ => system.log.info(s"No Forecast Feed defined.")
        arrivalsNoOp
    }

  override def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] =
    portCode.iata match {
      case "LHR" =>
        val host = config.get[String]("feeds.lhr.sftp.live.host")
        val username = config.get[String]("feeds.lhr.sftp.live.username")
        val password = config.get[String]("feeds.lhr.sftp.live.password")
        val contentProvider = () => LhrSftpLiveContentProvider(host, username, password).latestContent
        Feed(LHRFlightFeed(contentProvider, Feed.actorRefSource), 5.seconds, 1.minute)
      case "LGW" =>
        val lgwNamespace = params.maybeLGWNamespace.getOrElse(throw new Exception("Missing LGW Azure Namespace parameter"))
        val lgwSasToKey = params.maybeLGWSASToKey.getOrElse(throw new Exception("Missing LGW SAS Key for To Queue"))
        val lgwServiceBusUri = params.maybeLGWServiceBusUri.getOrElse(throw new Exception("Missing LGW Service Bus Uri"))
        val azureClient = LGWAzureClient(LGWFeed.serviceBusClient(lgwNamespace, lgwSasToKey, lgwServiceBusUri))
        Feed(LGWFeed(azureClient)(system).source(Feed.actorRefSource), 5.seconds, 100.milliseconds)
      case "BHX" if params.bhxIataEndPointUrl.nonEmpty =>
        Feed(BHXFeed(BHXClient(params.bhxIataUsername, params.bhxIataEndPointUrl), Feed.actorRefSource), 5.seconds, 80.seconds)
      case "LCY" if params.lcyLiveEndPointUrl.nonEmpty =>
        Feed(LCYFeed(LCYClient(ProdHttpClient(), params.lcyLiveUsername, params.lcyLiveEndPointUrl, params.lcyLiveUsername, params.lcyLivePassword), Feed.actorRefSource), 5.seconds, 80.seconds)
      case "LTN" =>
        val url = params.maybeLtnLiveFeedUrl.getOrElse(throw new Exception("Missing live feed url"))
        val username = params.maybeLtnLiveFeedUsername.getOrElse(throw new Exception("Missing live feed username"))
        val password = params.maybeLtnLiveFeedPassword.getOrElse(throw new Exception("Missing live feed password"))
        val token = params.maybeLtnLiveFeedToken.getOrElse(throw new Exception("Missing live feed token"))
        val timeZone = params.maybeLtnLiveFeedTimeZone match {
          case Some(tz) => DateTimeZone.forID(tz)
          case None => DateTimeZone.UTC
        }
        val requester = LtnFeedRequester(url, token, username, password)
        Feed(LtnLiveFeed(requester, timeZone).source(Feed.actorRefSource), 5.seconds, 30.seconds)
      case "MAN" | "STN" | "EMA" =>
        if (config.get[Boolean]("feeds.mag.use-legacy")) {
          log.info(s"Using legacy MAG live feed")
          Feed(createLiveChromaFlightFeed(ChromaLive).chromaVanillaFlights(Feed.actorRefSource), 5.seconds, 30.seconds)
        } else {
          log.info(s"Using new MAG live feed")
          val maybeFeed = for {
            privateKey <- config.getOptional[String]("feeds.mag.private-key")
            claimIss <- config.getOptional[String]("feeds.mag.claim.iss")
            claimRole <- config.getOptional[String]("feeds.mag.claim.role")
            claimSub <- config.getOptional[String]("feeds.mag.claim.sub")
          } yield {
            MagFeed(privateKey, claimIss, claimRole, claimSub, now, airportConfig.portCode, ProdFeedRequester).source(Feed.actorRefSource)
          }
          maybeFeed
            .map(f => Feed(f, 5.seconds, 30.seconds))
            .getOrElse({
              log.error(s"No feed credentials supplied. Live feed can't be set up")
              arrivalsNoOp
            })
        }
      case "GLA" =>
        val (url: String, username: String, password: String, token: String) = azinqConfig
        Feed(GlaFeed(url, username, password, token), 5.seconds, 1.minute)
      case "PIK" | "HUY" | "INV" | "NQY" | "NWI" | "SEN" =>
        Feed(CiriumFeed(config.get[String]("feeds.cirium.host"), portCode).source(Feed.actorRefSource), 5.seconds, 30.seconds)
      case "EDI" =>
        val (url: String, username: String, password: String, token: String) = azinqConfig
        Feed(EdiFeed(url, username, password, token), 5.seconds, 1.minute)
      case _ =>
        arrivalsNoOp
    }

  override def baseArrivalsSource(maybeAclFeed: Option[AclFeed]): Feed[typed.ActorRef[FeedTick]] = maybeAclFeed match {
    case None => arrivalsNoOp
    case Some(aclFeed) =>
      val initialDelay =
        if (config.get[Boolean]("acl.check-on-startup")) 10.seconds
        else AclFeed.delayUntilNextAclCheck(now(), 18) + (Math.random() * 60).minutes
      val frequency = 1.day

      log.info(s"Checking ACL every ${frequency.toHours} hours. Initial delay: ${initialDelay.toMinutes} minutes")

      Feed(Feed.actorRefSource.map { _ =>
        system.log.info(s"Requesting ACL feed")
        aclFeed.requestArrivals
      }, initialDelay, frequency)
  }

  override def liveBaseArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = {
    if (config.get[Boolean]("feature-flags.use-cirium-feed")) {
      log.info(s"Using Cirium Live Base Feed")
      Feed(CiriumFeed(config.get[String]("feeds.cirium.host"), portCode).source(Feed.actorRefSource), 5.seconds, 30.seconds)
    }
    else {
      log.info(s"Using Noop Base Live Feed")
      arrivalsNoOp
    }
  }
}
