package actors

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.AskableActorRef
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.{Deskstats, PaxFlow}
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.chroma.{ChromaFeedType, ChromaForecast, ChromaLive, DiffingStage}
import drt.http.ProdSendAndReceive
import drt.server.feeds.bhx.{BHXForecastFeed, BHXLiveFeed}
import drt.server.feeds.chroma.{ChromaForecastFeed, ChromaLiveFeed}
import drt.server.feeds.lgw.{LGWFeed, LGWForecastFeed}
import drt.server.feeds.lhr.live.LHRLiveFeed
import drt.server.feeds.lhr.{LHRFlightFeed, LHRForecastFeed}
import drt.server.feeds.ltn.LtnLiveFeed
import drt.shared.CrunchApi.{MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{Flights, TerminalName}
import drt.shared._
import org.apache.spark.sql.SparkSession
import org.joda.time.DateTimeZone
import play.api.Configuration
import server.feeds.acl.AclFeed
import server.feeds.{ArrivalsFeedSuccess, FeedResponse}
import services.PcpArrival.{GateOrStand, GateOrStandWalkTime, gateOrStandWalkTimeCalculator, walkTimeMillisProviderFromCsv}
import services.SplitsProvider.SplitProvider
import services._
import services.crunch.{CrunchProps, CrunchSystem}
import services.graphstages.Crunch.{oneDayMillis, oneMinuteMillis}
import services.graphstages._
import services.prediction.SparkSplitsPredictorFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

trait DrtSystemInterface {
  val now: () => SDateLike = () => SDate.now()

  val liveCrunchStateActor: ActorRef
  val forecastCrunchStateActor: ActorRef
  val shiftsActor: ActorRef
  val fixedPointsActor: ActorRef
  val staffMovementsActor: ActorRef

  val aclFeed: AclFeed

  def run(): Unit

  def getFeedStatus(): Future[Seq[FeedStatuses]]
}

case class DrtSystem(actorSystem: ActorSystem, config: Configuration, airportConfig: AirportConfig) extends DrtSystemInterface {

  implicit val system: ActorSystem = actorSystem

  val minutesToCrunch: Int = 1440
  val maxDaysToCrunch: Int = config.getInt("crunch.forecast.max_days").getOrElse(360)
  val aclPollMinutes: Int = config.getInt("crunch.forecast.poll_minutes").getOrElse(120)
  val snapshotIntervalVm: Int = config.getInt("persistence.snapshot-interval.voyage-manifest").getOrElse(1000)
  val expireAfterMillis: MillisSinceEpoch = 2 * oneDayMillis

  val ftpServer: String = ConfigFactory.load.getString("acl.host")
  val username: String = ConfigFactory.load.getString("acl.username")
  val path: String = ConfigFactory.load.getString("acl.keypath")

  val recrunchOnStart: Boolean = config.getBoolean("crunch.recrunch-on-start").getOrElse(false)
  system.log.info(s"recrunchOnStart: $recrunchOnStart")

  val aclFeed = AclFeed(ftpServer, username, path, airportConfig.portCode, aclTerminalMapping(airportConfig.portCode))

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))
  val actorMaterializer = ActorMaterializer()

  val purgeOldLiveSnapshots = false
  val purgeOldForecastSnapshots = true

  lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

  val liveCrunchStateProps = Props(classOf[CrunchStateActor], airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldLiveSnapshots)
  val forecastCrunchStateProps = Props(classOf[CrunchStateActor], 100, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldForecastSnapshots)

  lazy val liveCrunchStateActor: ActorRef = system.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
  lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor], now, expireAfterMillis, snapshotIntervalVm), name = "voyage-manifests-actor")
  lazy val forecastCrunchStateActor: ActorRef = system.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")
  val historicalSplitsProvider: SplitProvider = SplitsProvider.csvProvider
  lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor]))
  lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor]))
  lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor]))
  val useNationalityBasedProcessingTimes: Boolean = config.getString("feature-flags.nationality-based-processing-times").isDefined
  val useSplitsPrediction: Boolean = config.getString("feature-flags.use-splits-prediction").isDefined
  val rawSplitsUrl: String = config.getString("crunch.splits.raw-data-path").getOrElse("/dev/null")
  lazy val askableVoyageManifestsActor: AskableActorRef = voyageManifestsActor
  val splitsPredictorStage: SplitsPredictorBase = createSplitsPredictionStage(useSplitsPrediction, rawSplitsUrl)

  val dqZipBucketName: String = config.getString("dq.s3.bucket").getOrElse(throw new Exception("You must set DQ_S3_BUCKET for us to poll for AdvPaxInfo"))
  val apiS3PollFrequencyMillis: MillisSinceEpoch = config.getInt("dq.s3.poll_frequency_seconds").getOrElse(60) * 1000L

  lazy val voyageManifestsStage: Source[DqManifests, NotUsed] = Source.fromGraph(
    new VoyageManifestsGraphStage(
      dqZipBucketName,
      airportConfig.portCode,
      getLastSeenManifestsFileName,
      apiS3PollFrequencyMillis
    )
  )

  system.log.info(s"useNationalityBasedProcessingTimes: $useNationalityBasedProcessingTimes")
  system.log.info(s"useSplitsPrediction: $useSplitsPrediction")


  def run(): Unit = {
    val futurePortStates: Future[Seq[Option[Any]]] = Future.sequence(Seq(
      initialPortState(liveCrunchStateActor),
      initialPortState(forecastCrunchStateActor),
      initialArrivals(baseArrivalsActor),
      initialArrivals(forecastArrivalsActor),
      initialArrivals(liveArrivalsActor)))
    futurePortStates.onComplete {
      case Success(maybeLiveState :: maybeForecastState :: maybeBaseArrivals :: maybeForecastArrivals :: maybeLiveArrivals :: Nil) =>
        (maybeLiveState, maybeForecastState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals) match {
          case (initialLiveState: Option[PortState], initialForecastState: Option[PortState], initialBaseArrivals: Option[Set[Arrival]], initialForecastArrivals: Option[Set[Arrival]], initialLiveArrivals: Option[Set[Arrival]]) =>
            val initialPortState: Option[PortState] = mergePortStates(initialLiveState, initialForecastState)
            val crunchInputs: CrunchSystem[NotUsed, Cancellable] = startCrunchSystem(initialPortState, initialBaseArrivals, initialForecastArrivals, initialLiveArrivals, recrunchOnStart)
            subscribeStaffingActors(crunchInputs)
            startScheduledFeedImports(crunchInputs)
        }
      case Failure(error) =>
        system.log.error(s"Failed to restore initial state for App", error)
        None
    }
  }

  override def getFeedStatus(): Future[Seq[FeedStatuses]] = {
    val actors: Seq[AskableActorRef] = Seq(liveArrivalsActor, forecastArrivalsActor, baseArrivalsActor)

    val statuses = actors
      .map(askable => askable.ask(GetFeedStatuses)(new Timeout(5 seconds)).map {
        case Some(fs: FeedStatuses) => Option(fs)
        case _ => None
      })

    Future
      .sequence(statuses)
      .map(maybeStatuses => maybeStatuses.collect { case Some(fs) => fs })
  }

  def aclTerminalMapping(portCode: String): TerminalName => TerminalName = portCode match {
    case "LGW" => (tIn: TerminalName) => Map("1I" -> "S", "2I" -> "N").getOrElse(tIn, "")
    case "MAN" => (tIn: TerminalName) => Map("T1" -> "T1", "T2" -> "T2", "T3" -> "T3").getOrElse(tIn, "")
    case "EMA" => (tIn: TerminalName) => Map("1I" -> "T1", "1D" -> "T1").getOrElse(tIn, "")
    case _ => (tIn: TerminalName) => s"T${tIn.take(1)}"
  }

  def startScheduledFeedImports(crunchInputs: CrunchSystem[NotUsed, Cancellable]): Unit = {
    if (airportConfig.portCode == "LHR") config.getString("lhr.blackjack_url").map(csvUrl => {
      val requestIntervalMillis = 5 * oneMinuteMillis
      Deskstats.startBlackjack(csvUrl, crunchInputs.actualDeskStats, requestIntervalMillis milliseconds, SDate.now().addDays(-1))
    })
  }

  def subscribeStaffingActors(crunchInputs: CrunchSystem[NotUsed, Cancellable]): Unit = {
    shiftsActor ! AddShiftLikeSubscribers(List(crunchInputs.shifts))
    fixedPointsActor ! AddShiftLikeSubscribers(List(crunchInputs.fixedPoints))
    staffMovementsActor ! AddStaffMovementsSubscribers(List(crunchInputs.staffMovements))
  }

  def startCrunchSystem(initialPortState: Option[PortState],
                        initialBaseArrivals: Option[Set[Arrival]],
                        initialForecastArrivals: Option[Set[Arrival]],
                        initialLiveArrivals: Option[Set[Arrival]],
                        recrunchOnStart: Boolean
                       ): CrunchSystem[NotUsed, Cancellable] = {

    val crunchInputs = CrunchSystem(CrunchProps(
      system = system,
      airportConfig = airportConfig,
      pcpArrival = pcpArrivalTimeCalculator,
      historicalSplitsProvider = historicalSplitsProvider,
      liveCrunchStateActor = liveCrunchStateActor,
      forecastCrunchStateActor = forecastCrunchStateActor,
      maxDaysToCrunch = maxDaysToCrunch,
      expireAfterMillis = expireAfterMillis,
      actors = Map(
        "shifts" -> shiftsActor,
        "fixed-points" -> fixedPointsActor,
        "staff-movements" -> staffMovementsActor,
        "base-arrivals" -> baseArrivalsActor,
        "forecast-arrivals" -> forecastArrivalsActor,
        "live-arrivals" -> liveArrivalsActor
      ),
      useNationalityBasedProcessingTimes = useNationalityBasedProcessingTimes,
      splitsPredictorStage = splitsPredictorStage,
      manifestsSource = voyageManifestsStage,
      voyageManifestsActor = voyageManifestsActor,
      cruncher = TryRenjin.crunch,
      simulator = TryRenjin.runSimulationOfWork,
      initialPortState = initialPortState,
      initialBaseArrivals = initialBaseArrivals.getOrElse(Set()),
      initialFcstArrivals = initialForecastArrivals.getOrElse(Set()),
      initialLiveArrivals = initialLiveArrivals.getOrElse(Set()),
      arrivalsBaseSource = baseArrivalsSource(),
      arrivalsFcstSource = forecastArrivalsSource(airportConfig.portCode),
      arrivalsLiveSource = liveArrivalsSource(airportConfig.portCode),
      recrunchOnStart = recrunchOnStart
    ))
    crunchInputs
  }

  def initialPortState(askableCrunchStateActor: AskableActorRef): Future[Option[PortState]] = {
    askableCrunchStateActor.ask(GetState)(new Timeout(5 minutes)).map {
      case Some(ps: PortState) =>
        system.log.info(s"Got an initial port state from ${askableCrunchStateActor.toString} with ${ps.staffMinutes.size} staff minutes, ${ps.crunchMinutes.size} crunch minutes, and ${ps.flights.size} flights")
        Option(ps)
      case _ =>
        system.log.info(s"Got no initial port state from ${askableCrunchStateActor.toString}")
        None
    }
  }

  def initialArrivals(arrivalsActor: AskableActorRef): Future[Option[Set[Arrival]]] = {
    val canWaitMinutes = 10
    val arrivalsFuture: Future[Option[Set[Arrival]]] = arrivalsActor.ask(GetState)(new Timeout(canWaitMinutes minutes)).map {
      case ArrivalsState(arrivals, _) => Option(arrivals.values.toSet)
      case _ => None
    }

    arrivalsFuture.onComplete {
      case Success(arrivals) => arrivals
      case Failure(t) =>
        system.log.warning(s"Failed to get an initial ArrivalsState: $t")
        None
    }

    arrivalsFuture
  }

  def mergePortStates(maybeForecastPs: Option[PortState], maybeLivePs: Option[PortState]): Option[PortState] = (maybeForecastPs, maybeLivePs) match {
    case (None, None) => None
    case (Some(fps), None) => Option(fps)
    case (None, Some(lps)) => Option(lps)
    case (Some(fps), Some(lps)) =>
      Option(PortState(
        fps.flights ++ lps.flights,
        fps.crunchMinutes ++ lps.crunchMinutes,
        fps.staffMinutes ++ lps.staffMinutes))
  }

  def getLastSeenManifestsFileName: GateOrStand = {
    val futureLastSeenManifestFileName = askableVoyageManifestsActor.ask(GetState)(new Timeout(1 minute)).map {
      case VoyageManifestState(_, lastSeenFileName) => lastSeenFileName
    }
    Try {
      Await.result(futureLastSeenManifestFileName, 1 minute)
    } match {
      case Success(lastSeen) => lastSeen
      case Failure(t) =>
        system.log.warning(s"Failed to get last seen file name for DQ manifests: $t")
        ""
    }
  }

  def createSplitsPredictionStage(predictSplits: Boolean, rawSplitsUrl: String): SplitsPredictorBase = if (predictSplits)
    new SplitsPredictorStage(SparkSplitsPredictorFactory(createSparkSession(), rawSplitsUrl, airportConfig.portCode))
  else
    new DummySplitsPredictor()

  def createSparkSession(): SparkSession = {
    SparkSession
      .builder
      .appName("DRT Predictor")
      .config("spark.master", "local")
      .getOrCreate()
  }

  def liveArrivalsSource(portCode: String): Source[FeedResponse, Cancellable] = {
    val feed = portCode match {
      case "LHR" =>
        if (config.getString("feature-flags.lhr.use-new-lhr-feed").isDefined) {
          val apiUri = config.getString("lhr.live.api_url").get
          val token = config.getString("lhr.live.token").get
          system.log.info(s"Connecting to $apiUri using $token")

          LHRLiveFeed(apiUri, token, system)
        }
        else LHRFlightFeed()
      case "EDI" => createLiveChromaFlightFeed(ChromaLive).chromaEdiFlights()
      case "LGW" => LGWFeed()
      case "BHX" => BHXLiveFeed(config.getString("feeds.bhx.soap.endPointUrl").getOrElse(throw new Exception("Missing BHX live feed URL")))
      case "LTN" =>
        val url = config.getString("feeds.ltn.live.url").getOrElse(throw new Exception("Missing live feed url"))
        val username = config.getString("feeds.ltn.live.username").getOrElse(throw new Exception("Missing live feed username"))
        val password = config.getString("feeds.ltn.live.password").getOrElse(throw new Exception("Missing live feed password"))
        val token = config.getString("feeds.ltn.live.token").getOrElse(throw new Exception("Missing live feed token"))
        val timeZone = config.getString("feeds.ltn.live.timezone") match {
          case Some(tz) => DateTimeZone.forID(tz)
          case None => DateTimeZone.UTC
        }
        LtnLiveFeed(url, token, username, password, timeZone).tickingSource
      case _ => createLiveChromaFlightFeed(ChromaLive).chromaVanillaFlights(30 seconds)
    }
    feed
  }

  def forecastArrivalsSource(portCode: String): Source[FeedResponse, Cancellable] = {
    val forecastNoOp = Source.tick[FeedResponse](100 days, 100 days, ArrivalsFeedSuccess(Flights(Seq()), SDate.now()))
    val feed = portCode match {
      case "STN" => createForecastChromaFlightFeed(ChromaForecast).chromaVanillaFlights(30 minutes)
      case "LHR" => config.getString("lhr.forecast_path")
        .map(path => createForecastLHRFeed(path))
        .getOrElse(forecastNoOp)
      case "BHX" => BHXForecastFeed(config.getString("feeds.bhx.soap.endPointUrl").getOrElse(throw new Exception("Missing BHX forecast feed URL")))
      case "LGW" => LGWForecastFeed()
      case _ =>
        system.log.info(s"No Forecast Feed defined.")
        forecastNoOp
    }
    feed
  }

  def baseArrivalsSource(): Source[FeedResponse, Cancellable] = Source.tick(1 second, 60 minutes, NotUsed).map(_ => {
    system.log.info(s"Requesting ACL feed")
    aclFeed.requestArrivals
  })

  def walkTimeProvider(flight: Arrival): MillisSinceEpoch =
    gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, airportConfig.defaultWalkTimeMillis.getOrElse(flight.Terminal, 300000L))(flight)

  def pcpArrivalTimeCalculator: (Arrival) => MilliDate =
    PaxFlow.pcpArrivalTimeForFlight(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis)(walkTimeProvider)

  def createLiveChromaFlightFeed(feedType: ChromaFeedType): ChromaLiveFeed = {
    ChromaLiveFeed(system.log, new ChromaFetcher(feedType, system) with ProdSendAndReceive)
  }

  def createForecastChromaFlightFeed(feedType: ChromaFeedType): ChromaForecastFeed = {
    ChromaForecastFeed(system.log, new ChromaFetcherForecast(feedType, system) with ProdSendAndReceive)
  }

  def createForecastLHRFeed(lhrForecastPath: String): Source[FeedResponse, Cancellable] = {
    val imapServer = ConfigFactory.load().getString("lhr.forecast.imap_server")
    val imapPort = ConfigFactory.load().getInt("lhr.forecast.imap_port")
    val imapUsername = ConfigFactory.load().getString("lhr.forecast.imap_username")
    val imapPassword = ConfigFactory.load().getString("lhr.forecast.imap_password")
    val imapFromAddress = ConfigFactory.load().getString("lhr.forecast.from_address")
    val lhrForecastFeed = LHRForecastFeed(imapServer, imapUsername, imapPassword, imapFromAddress, imapPort)
    system.log.info(s"LHR Forecast: about to start ticking")
    Source.tick(10 seconds, 1 hour, {
      system.log.info(s"LHR Forecast: ticking")
      lhrForecastFeed.requestFeed
    }).via(DiffingStage.DiffLists)
  }
}
