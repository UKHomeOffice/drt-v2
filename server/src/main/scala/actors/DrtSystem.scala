package actors

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.{Deskstats, PaxFlow, UserRoleProviderLike}
import drt.chroma._
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.http.ProdSendAndReceive
import drt.server.feeds.api.S3ApiProvider
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
import play.api.mvc.{Headers, Session}
import server.feeds.acl.AclFeed
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess, ManifestsFeedResponse}
import services.PcpArrival.{GateOrStandWalkTime, gateOrStandWalkTimeCalculator, walkTimeMillisProviderFromCsv}
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

trait DrtSystemInterface extends UserRoleProviderLike {
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

case class DrtSystem(actorSystem: ActorSystem, config: Configuration, airportConfig: AirportConfig)
                    (implicit actorMaterializer: Materializer) extends DrtSystemInterface {

  implicit val system: ActorSystem = actorSystem

  val oneMegaByte: Int = 1024 * 1024

  val minutesToCrunch: Int = 1440
  val maxDaysToCrunch: Int = config.getOptional[Int]("crunch.forecast.max_days").getOrElse(360)
  val aclPollMinutes: Int = config.getOptional[Int]("crunch.forecast.poll_minutes").getOrElse(120)
  val snapshotIntervalVm: Int = config.getOptional[Int]("persistence.snapshot-interval.voyage-manifest").getOrElse(1000)
  val snapshotMegaBytesBaseArrivals: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.base-arrivals").getOrElse(1d) * oneMegaByte).toInt
  val snapshotMegaBytesFcstArrivals: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.forecast-arrivals").getOrElse(5d) * oneMegaByte).toInt
  val snapshotMegaBytesLiveArrivals: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.live-arrivals").getOrElse(2d) * oneMegaByte).toInt
  val snapshotMegaBytesFcstPortState: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.forecast-portstate").getOrElse(10d) * oneMegaByte).toInt
  val snapshotMegaBytesLivePortState: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.live-portstate").getOrElse(25d) * oneMegaByte).toInt
  val expireAfterMillis: MillisSinceEpoch = 2 * oneDayMillis

  val ftpServer: String = ConfigFactory.load.getString("acl.host")
  val username: String = ConfigFactory.load.getString("acl.username")
  val path: String = ConfigFactory.load.getString("acl.keypath")

  val recrunchOnStart: Boolean = config.getOptional[Boolean]("crunch.recrunch-on-start").getOrElse(false)
  system.log.info(s"recrunchOnStart: $recrunchOnStart")

  val aclFeed = AclFeed(ftpServer, username, path, airportConfig.feedPortCode, aclTerminalMapping(airportConfig.portCode))

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))

  val purgeOldLiveSnapshots = false
  val purgeOldForecastSnapshots = true

  lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor], snapshotMegaBytesBaseArrivals, now, expireAfterMillis), name = "base-arrivals-actor")
  lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastPortArrivalsActor], snapshotMegaBytesFcstArrivals, now, expireAfterMillis), name = "forecast-arrivals-actor")
  lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor], snapshotMegaBytesLiveArrivals, now, expireAfterMillis), name = "live-arrivals-actor")

  val liveCrunchStateProps = Props(classOf[CrunchStateActor], Option(airportConfig.portStateSnapshotInterval), snapshotMegaBytesLivePortState, "crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldLiveSnapshots)
  val forecastCrunchStateProps = Props(classOf[CrunchStateActor], Option(100), snapshotMegaBytesFcstPortState, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldForecastSnapshots)

  lazy val liveCrunchStateActor: ActorRef = system.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
  lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor], now, expireAfterMillis, snapshotIntervalVm), name = "voyage-manifests-actor")
  lazy val forecastCrunchStateActor: ActorRef = system.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")
  val historicalSplitsProvider: SplitProvider = SplitsProvider.csvProvider
  lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor]))
  lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor]))
  lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor]))
  val useNationalityBasedProcessingTimes: Boolean = config.getOptional[String]("feature-flags.nationality-based-processing-times").isDefined
  val useSplitsPrediction: Boolean = config.getOptional[String]("feature-flags.use-splits-prediction").isDefined
  val rawSplitsUrl: String = config.getOptional[String]("crunch.splits.raw-data-path").getOrElse("/dev/null")
  lazy val askableVoyageManifestsActor: AskableActorRef = voyageManifestsActor
  val splitsPredictorStage: SplitsPredictorBase = createSplitsPredictionStage(useSplitsPrediction, rawSplitsUrl)

  val dqZipBucketName: String = config.getOptional[String]("dq.s3.bucket").getOrElse(throw new Exception("You must set DQ_S3_BUCKET for us to poll for AdvPaxInfo"))
  val apiS3PollFrequencyMillis: MillisSinceEpoch = config.getOptional[Int]("dq.s3.poll_frequency_seconds").getOrElse(60) * 1000L
  val s3ApiProvider = S3ApiProvider(dqZipBucketName)
  val initialManifestsState: Option[VoyageManifestState] = manifestsState
  val maybeLatestZipFileName: String = initialManifestsState.map(_.latestZipFilename).getOrElse("")

  lazy val voyageManifestsStage: Source[ManifestsFeedResponse, NotUsed] = Source.fromGraph(
    new VoyageManifestsGraphStage(airportConfig.feedPortCode, s3ApiProvider, maybeLatestZipFileName, apiS3PollFrequencyMillis)
  )

  system.log.info(s"useNationalityBasedProcessingTimes: $useNationalityBasedProcessingTimes")
  system.log.info(s"useSplitsPrediction: $useSplitsPrediction")

  def getRoles(config: Configuration, headers: Headers, session: Session): List[String] =
    if (config.getOptional[String]("feature-flags.super-user-mode").isDefined) {
      system.log.info(s"Using Super User Roles")
      availableRoles
    } else userRolesFromHeader(headers)

  def run(): Unit = {
    val futurePortStates: Future[(Option[PortState], Option[PortState], Option[Set[Arrival]], Option[Set[Arrival]], Option[Set[Arrival]])] = {
      val maybeLivePortState = initialPortState(liveCrunchStateActor)
      val maybeForecastPortState = initialPortState(forecastCrunchStateActor)
      val maybeInitialBaseArrivals = initialArrivals(baseArrivalsActor)
      val maybeInitialFcstArrivals = initialArrivals(forecastArrivalsActor)
      val maybeInitialLiveArrivals = initialArrivals(liveArrivalsActor)
      for {
        lps <- maybeLivePortState
        fps <- maybeForecastPortState
        ba <- maybeInitialBaseArrivals
        fa <- maybeInitialFcstArrivals
        la <- maybeInitialLiveArrivals
      } yield (lps, fps, ba, fa, la)
    }
    futurePortStates.onComplete {
      case Success((maybeLiveState, maybeForecastState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals)) =>
        val initialPortState: Option[PortState] = mergePortStates(maybeLiveState, maybeForecastState)
        val crunchInputs: CrunchSystem[Cancellable, NotUsed] = startCrunchSystem(initialPortState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals, recrunchOnStart)
        subscribeStaffingActors(crunchInputs)
        startScheduledFeedImports(crunchInputs)
      case Failure(error) =>
        system.log.error(s"Failed to restore initial state for App", error)
        None
    }
  }

  override def getFeedStatus(): Future[Seq[FeedStatuses]] = {
    val actors: Seq[AskableActorRef] = Seq(liveArrivalsActor, forecastArrivalsActor, baseArrivalsActor, voyageManifestsActor)

    val statuses = actors
      .map(askable => askable.ask(GetFeedStatuses)(new Timeout(5 seconds)).map {
        case Some(fs: FeedStatuses) if fs.hasConnectedAtLeastOnce => Option(fs)
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

  def startScheduledFeedImports(crunchInputs: CrunchSystem[Cancellable, NotUsed]): Unit = {
    if (airportConfig.feedPortCode == "LHR") config.getOptional[String]("lhr.blackjack_url").map(csvUrl => {
      val requestIntervalMillis = 5 * oneMinuteMillis
      Deskstats.startBlackjack(csvUrl, crunchInputs.actualDeskStats, requestIntervalMillis milliseconds, SDate.now().addDays(-1))
    })
  }

  def subscribeStaffingActors(crunchInputs: CrunchSystem[Cancellable, NotUsed]): Unit = {
    shiftsActor ! AddShiftLikeSubscribers(List(crunchInputs.shifts))
    fixedPointsActor ! AddShiftLikeSubscribers(List(crunchInputs.fixedPoints))
    staffMovementsActor ! AddStaffMovementsSubscribers(List(crunchInputs.staffMovements))
  }

  def startCrunchSystem(initialPortState: Option[PortState],
                        initialBaseArrivals: Option[Set[Arrival]],
                        initialForecastArrivals: Option[Set[Arrival]],
                        initialLiveArrivals: Option[Set[Arrival]],
                        recrunchOnStart: Boolean
                       ): CrunchSystem[Cancellable, NotUsed] = {

    val crunchInputs = CrunchSystem(CrunchProps(
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
      initialManifestsState = initialManifestsState,
      arrivalsBaseSource = baseArrivalsSource(),
      arrivalsFcstSource = forecastArrivalsSource(airportConfig.feedPortCode),
      arrivalsLiveSource = liveArrivalsSource(airportConfig.feedPortCode),
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
    val canWaitMinutes = 60
    val arrivalsFuture: Future[Option[Set[Arrival]]] = arrivalsActor.ask(GetState)(new Timeout(canWaitMinutes minutes)).map {
      case ArrivalsState(arrivals, _, _) => Option(arrivals.values.toSet)
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

  def mergePortStates(maybeForecastPs: Option[PortState],
                      maybeLivePs: Option[PortState]): Option[PortState] = (maybeForecastPs, maybeLivePs) match {
    case (None, None) => None
    case (Some(fps), None) => Option(fps)
    case (None, Some(lps)) => Option(lps)
    case (Some(fps), Some(lps)) =>
      Option(PortState(
        fps.flights ++ lps.flights,
        fps.crunchMinutes ++ lps.crunchMinutes,
        fps.staffMinutes ++ lps.staffMinutes))
  }

  def manifestsState: Option[VoyageManifestState] = {
    val futureVoyageManifestState = askableVoyageManifestsActor.ask(GetState)(new Timeout(1 minute)).map {
      case s: VoyageManifestState => s
    }
    Try {
      Await.result(futureVoyageManifestState, 1 minute)
    } match {
      case Success(state) => Option(state)
      case Failure(t) =>
        system.log.warning(s"Failed to get last seen file name for DQ manifests: $t")
        None
    }
  }

  def createSplitsPredictionStage(predictSplits: Boolean,
                                  rawSplitsUrl: String): SplitsPredictorBase = if (predictSplits)
    new SplitsPredictorStage(SparkSplitsPredictorFactory(createSparkSession(), rawSplitsUrl, airportConfig.feedPortCode))
  else
    new DummySplitsPredictor()

  def createSparkSession(): SparkSession = {
    SparkSession
      .builder
      .appName("DRT Predictor")
      .config("spark.master", "local")
      .getOrCreate()
  }

  def liveArrivalsSource(portCode: String): Source[ArrivalsFeedResponse, Cancellable] = {
    val feed = portCode match {
      case "LHR" =>
        if (config.getOptional[String]("feature-flags.lhr.use-new-lhr-feed").isDefined) {
          val apiUri = config.getOptional[String]("lhr.live.api_url").get
          val token = config.getOptional[String]("lhr.live.token").get
          system.log.info(s"Connecting to $apiUri using $token")

          LHRLiveFeed(apiUri, token, system)
        }
        else LHRFlightFeed()
      case "EDI" => createLiveChromaFlightFeed(ChromaLive).chromaEdiFlights()
      case "LGW" => LGWFeed()
      case "BHX" => BHXLiveFeed(config.getOptional[String]("feeds.bhx.soap.endPointUrl").getOrElse(throw new Exception("Missing BHX live feed URL")))
      case "LTN" =>
        val url = config.getOptional[String]("feeds.ltn.live.url").getOrElse(throw new Exception("Missing live feed url"))
        val username = config.getOptional[String]("feeds.ltn.live.username").getOrElse(throw new Exception("Missing live feed username"))
        val password = config.getOptional[String]("feeds.ltn.live.password").getOrElse(throw new Exception("Missing live feed password"))
        val token = config.getOptional[String]("feeds.ltn.live.token").getOrElse(throw new Exception("Missing live feed token"))
        val timeZone = config.getOptional[String]("feeds.ltn.live.timezone") match {
          case Some(tz) => DateTimeZone.forID(tz)
          case None => DateTimeZone.UTC
        }
        LtnLiveFeed(url, token, username, password, timeZone).tickingSource
      case _ => createLiveChromaFlightFeed(ChromaLive).chromaVanillaFlights(30 seconds)
    }
    feed
  }

  def forecastArrivalsSource(portCode: String): Source[ArrivalsFeedResponse, Cancellable] = {
    val forecastNoOp = Source.tick[ArrivalsFeedResponse](100 days, 100 days, ArrivalsFeedSuccess(Flights(Seq()), SDate.now()))
    val feed = portCode match {
      case "STN" => createForecastChromaFlightFeed(ChromaForecast).chromaVanillaFlights(30 minutes)
      case "LHR" => config.getOptional[String]("lhr.forecast_path")
        .map(path => createForecastLHRFeed(path))
        .getOrElse(forecastNoOp)
      case "BHX" => BHXForecastFeed(config.getOptional[String]("feeds.bhx.soap.endPointUrl").getOrElse(throw new Exception("Missing BHX forecast feed URL")))
      case "LGW" => LGWForecastFeed()
      case _ =>
        system.log.info(s"No Forecast Feed defined.")
        forecastNoOp
    }
    feed
  }

  def baseArrivalsSource(): Source[ArrivalsFeedResponse, Cancellable] = Source.tick(1 second, 60 minutes, NotUsed).map(_ => {
    system.log.info(s"Requesting ACL feed")
    aclFeed.requestArrivals
  })

  def walkTimeProvider(flight: Arrival): MillisSinceEpoch =
    gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, airportConfig.defaultWalkTimeMillis.getOrElse(flight.Terminal, 300000L))(flight)

  def pcpArrivalTimeCalculator: Arrival => MilliDate =
    PaxFlow.pcpArrivalTimeForFlight(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis)(walkTimeProvider)

  def createLiveChromaFlightFeed(feedType: ChromaFeedType): ChromaLiveFeed = {
    ChromaLiveFeed(system.log, new ChromaFetcher(feedType, system) with ProdSendAndReceive)
  }

  def createForecastChromaFlightFeed(feedType: ChromaFeedType): ChromaForecastFeed = {
    ChromaForecastFeed(system.log, new ChromaFetcherForecast(feedType, system) with ProdSendAndReceive)
  }

  def createForecastLHRFeed(lhrForecastPath: String): Source[ArrivalsFeedResponse, Cancellable] = {
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
    })
  }
}
