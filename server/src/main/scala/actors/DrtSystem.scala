package actors

import actors.Sizes.oneMegaByte
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.ConfigFactory
import controllers.{Deskstats, PaxFlow, UserRoleProviderLike}
import drt.chroma._
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.http.ProdSendAndReceive
import drt.server.feeds.api.S3ApiProvider
import drt.server.feeds.bhx.{BHXForecastFeed, BHXLiveFeed}
import drt.server.feeds.chroma.{ChromaForecastFeed, ChromaLiveFeed}
import drt.server.feeds.lgw.{LGWFeed, LGWForecastFeed}
import drt.server.feeds.lhr.{LHRFlightFeed, LHRForecastFeed}
import drt.server.feeds.lhr.live.LegacyLhrLiveContentProvider
import drt.server.feeds.lhr.sftp.LhrSftpLiveContentProvider
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
import slickdb.{ArrivalTable, Tables}

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
  val alertsActor: ActorRef
  val arrivalsImportActor: ActorRef

  val aclFeed: AclFeed

  def run(): Unit

  def getFeedStatus: Future[Seq[FeedStatuses]]
}

object DrtStaticParameters {
  val expireAfterMillis: MillisSinceEpoch = 2 * oneDayMillis

  def time48HoursAgo(now: () => SDateLike): () => SDateLike = () => now().addDays(-2)

  def timeBeforeThisMonth(now: () => SDateLike): () => SDateLike = () => now().startOfTheMonth()
}

object PostgresTables extends {
  val profile = slick.jdbc.PostgresProfile
} with Tables

case class DrtConfigParameters(config: Configuration) {
  val maxDaysToCrunch: Int = config.getOptional[Int]("crunch.forecast.max_days").getOrElse(360)
  val aclPollMinutes: Int = config.getOptional[Int]("crunch.forecast.poll_minutes").getOrElse(120)
  val snapshotIntervalVm: Int = config.getOptional[Int]("persistence.snapshot-interval.voyage-manifest").getOrElse(1000)
  val snapshotMegaBytesBaseArrivals: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.base-arrivals").getOrElse(1d) * oneMegaByte).toInt
  val snapshotMegaBytesFcstArrivals: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.forecast-arrivals").getOrElse(5d) * oneMegaByte).toInt
  val snapshotMegaBytesLiveArrivals: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.live-arrivals").getOrElse(2d) * oneMegaByte).toInt
  val snapshotMegaBytesFcstPortState: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.forecast-portstate").getOrElse(10d) * oneMegaByte).toInt
  val snapshotMegaBytesLivePortState: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.live-portstate").getOrElse(25d) * oneMegaByte).toInt
  val snapshotMegaBytesVoyageManifests: Int = (config.getOptional[Double]("persistence.snapshot-megabytes.voyage-manifest").getOrElse(100d) * oneMegaByte).toInt
  val awSCredentials: AWSCredentials = new AWSCredentials {
    override def getAWSAccessKeyId: TerminalName = config.getOptional[String]("aws.credentials.access_key_id").getOrElse("")

    override def getAWSSecretKey: TerminalName = config.getOptional[String]("aws.credentials.secret_key").getOrElse("")
  }
  val ftpServer: String = ConfigFactory.load.getString("acl.host")
  val username: String = ConfigFactory.load.getString("acl.username")
  val path: String = ConfigFactory.load.getString("acl.keypath")
  val recrunchOnStart: Boolean = config.getOptional[Boolean]("crunch.recrunch-on-start").getOrElse(false)
  val useNationalityBasedProcessingTimes: Boolean = config.getOptional[String]("feature-flags.nationality-based-processing-times").isDefined
  val useSplitsPrediction: Boolean = config.getOptional[String]("feature-flags.use-splits-prediction").isDefined
  val rawSplitsUrl: String = config.getOptional[String]("crunch.splits.raw-data-path").getOrElse("/dev/null")
  val dqZipBucketName: String = config.getOptional[String]("dq.s3.bucket").getOrElse(throw new Exception("You must set DQ_S3_BUCKET for us to poll for AdvPaxInfo"))
  val apiS3PollFrequencyMillis: MillisSinceEpoch = config.getOptional[Int]("dq.s3.poll_frequency_seconds").getOrElse(60) * 1000L
  val isSuperUserMode: Boolean = config.getOptional[String]("feature-flags.super-user-mode").isDefined
  val maybeBlackJackUrl: Option[String] = config.getOptional[String]("feeds.lhr.blackjack_url")

  val useNewLhrFeed: Boolean = config.getOptional[String]("feature-flags.lhr.use-new-lhr-feed").isDefined
  val newLhrFeedApiUrl: String = config.getOptional[String]("feeds.lhr.live.api_url").getOrElse("")
  val newLhrFeedApiToken: String = config.getOptional[String]("feeds.lhr.live.token").getOrElse("")

  val maybeBhxSoapEndPointUrl: Option[String] = config.getOptional[String]("feeds.bhx.soap.endPointUrl")

  val maybeLtnLiveFeedUrl: Option[String] = config.getOptional[String]("feeds.ltn.live.url")
  val maybeLtnLiveFeedUsername: Option[String] = config.getOptional[String]("feeds.ltn.live.username")
  val maybeLtnLiveFeedPassword: Option[String] = config.getOptional[String]("feeds.ltn.live.password")
  val maybeLtnLiveFeedToken: Option[String] = config.getOptional[String]("feeds.ltn.live.token")
  val maybeLtnLiveFeedTimeZone: Option[String] = config.getOptional[String]("feeds.ltn.live.timezone")

  val maybeLGWNamespace: Option[String] = config.getOptional[String]("feeds.lgw.live.azure.namespace")
  val maybeLGWSASToKey: Option[String] = config.getOptional[String]("feeds.lgw.live.azure.sas_to_Key")
  val maybeLGWServiceBusUri: Option[String] = config.getOptional[String]("feeds.lgw.live.azure.service_bus_uri")
}

case class DrtSystem(actorSystem: ActorSystem, config: Configuration, airportConfig: AirportConfig)
                    (implicit actorMaterializer: Materializer) extends DrtSystemInterface {

  implicit val system: ActorSystem = actorSystem

  val params = DrtConfigParameters(config)

  import DrtStaticParameters._

  system.log.info(s"recrunchOnStart: ${params.recrunchOnStart}")

  val aclFeed = AclFeed(params.ftpServer, params.username, params.path, airportConfig.feedPortCode, aclTerminalMapping(airportConfig.portCode))

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))

  val aggregateArrivalsDbConfigKey = "aggregated-db"

  val purgeOldLiveSnapshots = false
  val purgeOldForecastSnapshots = true

  val liveCrunchStateProps = Props(classOf[CrunchStateActor], Option(airportConfig.portStateSnapshotInterval), params.snapshotMegaBytesLivePortState, "crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldLiveSnapshots)
  val forecastCrunchStateProps = Props(classOf[CrunchStateActor], Option(100), params.snapshotMegaBytesFcstPortState, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldForecastSnapshots)

  lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor], params.snapshotMegaBytesBaseArrivals, now, expireAfterMillis), name = "base-arrivals-actor")
  lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastPortArrivalsActor], params.snapshotMegaBytesFcstArrivals, now, expireAfterMillis), name = "forecast-arrivals-actor")
  lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor], params.snapshotMegaBytesLiveArrivals, now, expireAfterMillis), name = "live-arrivals-actor")

  lazy val arrivalsImportActor: ActorRef = system.actorOf(Props(classOf[ArrivalsImportActor]), name = "arrivals-import-actor")

  lazy val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(classOf[AggregatedArrivalsActor], airportConfig.portCode, ArrivalTable(airportConfig.portCode, PostgresTables)), name = "aggregated-arrivals-actor")

  lazy val liveCrunchStateActor: ActorRef = system.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
  lazy val forecastCrunchStateActor: ActorRef = system.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")

  lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor], params.snapshotMegaBytesVoyageManifests, now, expireAfterMillis, params.snapshotIntervalVm), name = "voyage-manifests-actor")

  lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor], now, timeBeforeThisMonth(now)))
  lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor], now))
  lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor], now, time48HoursAgo(now)))

  lazy val alertsActor: ActorRef = system.actorOf(Props(classOf[AlertsActor]))
  val historicalSplitsProvider: SplitProvider = SplitsProvider.csvProvider
  lazy val askableVoyageManifestsActor: AskableActorRef = voyageManifestsActor
  val splitsPredictorStage: SplitsPredictorBase = createSplitsPredictionStage(params.useSplitsPrediction, params.rawSplitsUrl)

  val s3ApiProvider = S3ApiProvider(params.awSCredentials, params.dqZipBucketName)
  val initialManifestsState: Option[VoyageManifestState] = manifestsState
  val maybeLatestZipFileName: String = initialManifestsState.map(_.latestZipFilename).getOrElse("")

  lazy val voyageManifestsStage: Source[ManifestsFeedResponse, NotUsed] = Source.fromGraph(
    new VoyageManifestsGraphStage(airportConfig.feedPortCode, s3ApiProvider, maybeLatestZipFileName, params.apiS3PollFrequencyMillis)
  )

  system.log.info(s"useNationalityBasedProcessingTimes: ${params.useNationalityBasedProcessingTimes}")
  system.log.info(s"useSplitsPrediction: ${params.useSplitsPrediction}")

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] =
    if (params.isSuperUserMode) {
      system.log.info(s"Using Super User Roles")
      Roles.availableRoles
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
        system.log.info(s"Successfully restored initial state for App")
        val initialPortState: Option[PortState] = mergePortStates(maybeLiveState, maybeForecastState)
        val crunchInputs: CrunchSystem[Cancellable, NotUsed] = startCrunchSystem(initialPortState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals, params.recrunchOnStart)
        subscribeStaffingActors(crunchInputs)
        startScheduledFeedImports(crunchInputs)
      case Failure(error) =>
        system.log.error(s"Failed to restore initial state for App", error)
        None
    }
  }

  override def getFeedStatus: Future[Seq[FeedStatuses]] = {
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
    if (airportConfig.feedPortCode == "LHR") params.maybeBlackJackUrl.map(csvUrl => {
      val requestIntervalMillis = 5 * oneMinuteMillis
      Deskstats.startBlackjack(csvUrl, crunchInputs.actualDeskStats, requestIntervalMillis milliseconds, SDate.now().addDays(-1))
    })
  }

  def subscribeStaffingActors(crunchInputs: CrunchSystem[Cancellable, NotUsed]): Unit = {
    shiftsActor ! AddShiftSubscribers(List(crunchInputs.shifts))
    fixedPointsActor ! AddFixedPointSubscribers(List(crunchInputs.fixedPoints))
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
      maxDaysToCrunch = params.maxDaysToCrunch,
      expireAfterMillis = expireAfterMillis,
      actors = Map(
        "shifts" -> shiftsActor,
        "fixed-points" -> fixedPointsActor,
        "staff-movements" -> staffMovementsActor,
        "base-arrivals" -> baseArrivalsActor,
        "forecast-arrivals" -> forecastArrivalsActor,
        "live-arrivals" -> liveArrivalsActor,
        "aggregated-arrivals" -> aggregatedArrivalsActor
      ),
      useNationalityBasedProcessingTimes = params.useNationalityBasedProcessingTimes,
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
        val contentProvider = if (config.get[Boolean]("feeds.lhr.use-legacy-live")) {
          log.info(s"Using legacy LHR live feed")
          () => LegacyLhrLiveContentProvider().csvContentsProviderProd()
        } else {
          log.info(s"Using new LHR live feed")
          val host = config.get[String]("feeds.lhr.sftp.live.host")
          val username = config.get[String]("feeds.lhr.sftp.live.username")
          val password = config.get[String]("feeds.lhr.sftp.live.password")
          () => LhrSftpLiveContentProvider(host, username, password).latestContent
        }
        LHRFlightFeed(contentProvider)
      case "EDI" => createLiveChromaFlightFeed(ChromaLive).chromaEdiFlights()
      case "LGW" =>
        val lgwNamespace = params.maybeLGWNamespace.getOrElse(throw new Exception("Missing LGW Azure Namespace parameter"))
        val lgwSasToKey = params.maybeLGWNamespace.getOrElse(throw new Exception("Missing LGW SAS Key for To Queue"))
        val lgwServiceBusUri = params.maybeLGWServiceBusUri.getOrElse(throw new Exception("Missing LGW Service Bus Uri"))
        LGWFeed(lgwNamespace, lgwSasToKey, lgwServiceBusUri)(system).source()
      case "BHX" => BHXLiveFeed(params.maybeBhxSoapEndPointUrl.getOrElse(throw new Exception("Missing BHX live feed URL")))
      case "LTN" =>
        val url = params.maybeLtnLiveFeedUrl.getOrElse(throw new Exception("Missing live feed url"))
        val username = params.maybeLtnLiveFeedUsername.getOrElse(throw new Exception("Missing live feed username"))
        val password = params.maybeLtnLiveFeedPassword.getOrElse(throw new Exception("Missing live feed password"))
        val token = params.maybeLtnLiveFeedToken.getOrElse(throw new Exception("Missing live feed token"))
        val timeZone = params.maybeLtnLiveFeedTimeZone match {
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
      case "LHR" => createForecastLHRFeed()
      case "BHX" => BHXForecastFeed(params.maybeBhxSoapEndPointUrl.getOrElse(throw new Exception("Missing BHX feed URL")))
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

  def createForecastLHRFeed(): Source[ArrivalsFeedResponse, Cancellable] = {
    val lhrForecastFeed = LHRForecastFeed(arrivalsImportActor)
    Source
      .tick(10 seconds, 60 seconds, NotUsed)
      .map(_ => lhrForecastFeed.requestFeed)
  }
}
