package controllers

import java.nio.ByteBuffer

import actors._
import actors.pointInTime.CrunchStateReadActor
import akka.NotUsed
import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern.{AskableActorRef, _}
import akka.stream._
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import boopickle.Default._
import buildinfo.BuildInfo
import com.google.inject.{Inject, Singleton}
import com.typesafe.config.ConfigFactory
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFetcherForecast}
import drt.chroma.{ChromaFeedType, ChromaForecast, ChromaLive, DiffingStage}
import drt.http.ProdSendAndReceive
import drt.server.feeds.chroma.{ChromaForecastFeed, ChromaLiveFeed}
import drt.server.feeds.lgw.LGWFeed
import drt.server.feeds.lhr.live.LHRLiveFeed
import drt.server.feeds.lhr.{LHRFlightFeed, LHRForecastFeed}
import drt.shared.CrunchApi.{groupCrunchMinutesByX, _}
import drt.shared.FlightsApi.{Flights, TerminalName}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{AirportConfig, Api, Arrival, _}
import drt.staff.ImportStaff
import org.apache.spark.sql.SparkSession
import org.joda.time.chrono.ISOChronology
import org.slf4j.{Logger, LoggerFactory}
import play.api.http.{HeaderNames, HttpEntity}
import play.api.mvc._
import play.api.{Configuration, Environment}
import server.feeds.acl.AclFeed
import services.PcpArrival._
import services.SDate.implicits._
import services.SplitsProvider.SplitProvider
import services.crunch.{CrunchProps, CrunchSystem}
import services.graphstages.Crunch._
import services.graphstages._
import services.prediction.SparkSplitsPredictorFactory
import services.shifts.StaffTimeSlots
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.PaxTypeAndQueueCount
import services.{SDate, _}

import scala.collection.immutable.{IndexedSeq, Map}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}
//import scala.collection.immutable.Seq // do not import this here, it would break autowire.
import services.PcpArrival.{gateOrStandWalkTimeCalculator, pcpFrom, walkTimeMillisProviderFromCsv}

object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {

  import scala.language.experimental.macros

  override def read[R: Pickler](p: ByteBuffer): R = Unpickle[R].fromBytes(p)

  def myroute[Trait](target: Trait): Router = macro MyMacros.routeMacro[Trait, ByteBuffer]

  override def write[R: Pickler](r: R): ByteBuffer = Pickle.intoBytes(r)
}

object PaxFlow {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def makeFlightPaxFlowCalculator(splitRatioForFlight: (Arrival) => Option[SplitRatios],
                                  bestPax: (Arrival) => Int): (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val provider = PaxLoadCalculator.flightPaxFlowProvider(splitRatioForFlight, bestPax)
    (arrival) => {
      val pax = bestPax(arrival)
      val paxFlow = provider(arrival)
      val summedPax = paxFlow.map(_._2.paxSum).sum
      val firstPaxTime = paxFlow.headOption.map(pf => SDate(pf._1).toString)
      log.debug(s"${Arrival.summaryString(arrival)} pax: $pax, summedFlowPax: $summedPax, deltaPax: ${pax - summedPax}, firstPaxTime: $firstPaxTime")
      paxFlow
    }
  }

  def splitRatioForFlight(splitsProviders: List[SplitProvider])(flight: Arrival): Option[SplitRatios] = SplitsProvider.splitsForFlight(splitsProviders)(flight)

  def pcpArrivalTimeForFlight(timeToChoxMillis: MillisSinceEpoch, firstPaxOffMillis: MillisSinceEpoch)
                             (walkTimeProvider: FlightWalkTime)
                             (flight: Arrival): MilliDate = pcpFrom(timeToChoxMillis, firstPaxOffMillis, walkTimeProvider)(flight)
}

trait SystemActors {
  self: AirportConfProvider =>

  implicit val system: ActorSystem

  val config: Configuration

  val minutesToCrunch: Int = 1440
  val maxDaysToCrunch: Int = config.getInt("crunch.forecast.max_days").getOrElse(360)
  val aclPollMinutes: Int = config.getInt("crunch.forecast.poll_minutes").getOrElse(120)
  val expireAfterMillis: MillisSinceEpoch = 2 * oneDayMillis
  val now: () => SDateLike = () => SDate.now()

  val ftpServer: String = ConfigFactory.load.getString("acl.host")
  val username: String = ConfigFactory.load.getString("acl.username")
  val path: String = ConfigFactory.load.getString("acl.keypath")

  val aclFeed = AclFeed(ftpServer, username, path, airportConfig.portCode)

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))
  val actorMaterializer = ActorMaterializer()

  val purgeOldLiveSnapshots = false
  val purgeOldForecastSnapshots = true

  val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

  val liveCrunchStateProps = Props(classOf[CrunchStateActor], airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldLiveSnapshots)
  val forecastCrunchStateProps = Props(classOf[CrunchStateActor], 100, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldForecastSnapshots)

  val liveCrunchStateActor: ActorRef = system.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
  val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor], now, expireAfterMillis), name = "voyage-manifests-actor")
  val forecastCrunchStateActor: ActorRef = system.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")
  val historicalSplitsProvider: SplitProvider = SplitsProvider.csvProvider
  val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor]))
  val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor]))
  val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor]))
  val useNationalityBasedProcessingTimes: Boolean = config.getString("feature-flags.nationality-based-processing-times").isDefined
  val useSplitsPrediction: Boolean = config.getString("feature-flags.use-splits-prediction").isDefined
  val rawSplitsUrl: String = config.getString("crunch.splits.raw-data-path").getOrElse("/dev/null")
  val dqZipBucketName: String = config.getString("dq.s3.bucket").getOrElse(throw new Exception("You must set DQ_S3_BUCKET for us to poll for AdvPaxInfo"))
  val askableVoyageManifestsActor: AskableActorRef = voyageManifestsActor

  val splitsPredictorStage: SplitsPredictorBase = createSplitsPredictionStage(useSplitsPrediction, rawSplitsUrl)
  val apiS3PollFrequencyMillis: MillisSinceEpoch = config.getInt("dq.s3.poll_frequency_seconds").getOrElse(60) * 1000L
  val voyageManifestsStage: Source[DqManifests, NotUsed] = Source.fromGraph(
    new VoyageManifestsGraphStage(
      dqZipBucketName,
      airportConfig.portCode,
      getLastSeenManifestsFileName,
      apiS3PollFrequencyMillis
    )
  )

  system.log.info(s"useNationalityBasedProcessingTimes: $useNationalityBasedProcessingTimes")
  system.log.info(s"useSplitsPrediction: $useSplitsPrediction")

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
          val crunchInputs: CrunchSystem[NotUsed] = startCrunchSystem(initialPortState, initialBaseArrivals, initialForecastArrivals, initialLiveArrivals)
          subscribeStaffingActors(crunchInputs)
          startScheduledFeedImports(crunchInputs)
      }
  }

  def startScheduledFeedImports(crunchInputs: CrunchSystem[NotUsed]): Unit = {
    liveArrivalsSource(airportConfig.portCode)
      .runForeach(f => crunchInputs.liveArrivals.offer(f))(actorMaterializer)
    forecastArrivalsSource(airportConfig.portCode)
      .runForeach(f => crunchInputs.forecastArrivals.offer(f))(actorMaterializer)

    system.scheduler.schedule(0 milliseconds, aclPollMinutes minutes) {
      crunchInputs.baseArrivals.offer(aclFeed.arrivals)
    }

    if (portCode == "LHR") config.getString("lhr.blackjack_url").map(csvUrl => {
      val requestIntervalMillis = 5 * oneMinuteMillis
      Deskstats.startBlackjack(csvUrl, crunchInputs.actualDeskStats, requestIntervalMillis milliseconds, SDate.now().addDays(-1))
    })
  }

  def subscribeStaffingActors(crunchInputs: CrunchSystem[NotUsed]): Unit = {
    shiftsActor ! AddShiftLikeSubscribers(List(crunchInputs.shifts))
    fixedPointsActor ! AddShiftLikeSubscribers(List(crunchInputs.fixedPoints))
    staffMovementsActor ! AddStaffMovementsSubscribers(List(crunchInputs.staffMovements))
  }

  def startCrunchSystem(initialPortState: Option[PortState], initialBaseArrivals: Option[Set[Arrival]], initialForecastArrivals: Option[Set[Arrival]], initialLiveArrivals: Option[Set[Arrival]]): CrunchSystem[NotUsed] = {
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
      initialLiveArrivals = initialLiveArrivals.getOrElse(Set())
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
    val canWaitMinutes = 5
    val arrivalsFuture: Future[Option[Set[Arrival]]] = arrivalsActor.ask(GetState)(new Timeout(canWaitMinutes minutes)).map {
      case ArrivalsState(arrivals) => Option(arrivals.values.toSet)
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
    new SplitsPredictorStage(SparkSplitsPredictorFactory(createSparkSession(), rawSplitsUrl, portCode))
  else
    new DummySplitsPredictor()

  def createSparkSession(): SparkSession = {
    SparkSession
      .builder
      .appName("DRT Predictor")
      .config("spark.master", "local")
      .getOrCreate()
  }

  def liveArrivalsSource(portCode: String): Source[Flights, Cancellable] = {
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
      case _ => createLiveChromaFlightFeed(ChromaLive).chromaVanillaFlights(30 seconds)
    }
    feed.map(Flights)
  }

  def forecastArrivalsSource(portCode: String): Source[Flights, Cancellable] = {
    val forecastNoOp = Source.tick[List[Arrival]](0 seconds, 30 minutes, List())
    val feed = portCode match {
      case "STN" => createForecastChromaFlightFeed(ChromaForecast).chromaVanillaFlights(30 minutes)
      case "LHR" => config.getString("lhr.forecast_path")
        .map(path => createForecastLHRFeed(path))
        .getOrElse(forecastNoOp)
      case _ =>
        forecastNoOp
    }
    feed.map(Flights)
  }

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

  def createForecastLHRFeed(lhrForecastPath: String): Source[List[Arrival], Cancellable] = {
    val imapServer = ConfigFactory.load().getString("lhr.forecast.imap_server")
    val imapPort = ConfigFactory.load().getInt("lhr.forecast.imap_port")
    val imapUsername = ConfigFactory.load().getString("lhr.forecast.imap_username")
    val imapPassword = ConfigFactory.load().getString("lhr.forecast.imap_password")
    val imapFromAddress = ConfigFactory.load().getString("lhr.forecast.from_address")
    val lhrForecastFeed = LHRForecastFeed(imapServer, imapUsername, imapPassword, imapFromAddress, imapPort)
    system.log.info(s"LHR Forecast: about to start ticking")
    Source.tick(10 seconds, 1 hour, {
      system.log.info(s"LHR Forecast: ticking")
      lhrForecastFeed.arrivals
    }).via(DiffingStage.DiffLists[Arrival]()).map(_.toList)
  }
}

trait AirportConfiguration {
  def airportConfig: AirportConfig
}

trait AirportConfProvider extends AirportConfiguration {
  val portCode: String = ConfigFactory.load().getString("portcode").toUpperCase
  val config: Configuration

  def mockProd: String = sys.env.getOrElse("MOCK_PROD", "PROD").toUpperCase

  def useStaffingInput: Boolean = config.getString("feature-flags.use-v2-staff-input").isDefined

  def contactEmail: Option[String] = config.getString("contact-email")

  def getPortConfFromEnvVar: AirportConfig = AirportConfigs.confByPort(portCode)

  def airportConfig: AirportConfig = getPortConfFromEnvVar.copy(
    useStaffingInput = useStaffingInput,
    contactEmail = contactEmail
  )
}

trait ProdPassengerSplitProviders {
  self: AirportConfiguration with SystemActors =>

  val csvSplitsProvider: SplitsProvider.SplitProvider = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: Arrival): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight.IATA, MilliDate(apiFlight.Scheduled)), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: Arrival): Option[FastTrackPercentages] =
    Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight.IATA, MilliDate(apiFlight.Scheduled)), 0d, 0d))

  private implicit val timeout: Timeout = Timeout(250 milliseconds)
}

trait ImplicitTimeoutProvider {
  implicit val timeout: Timeout = Timeout(1 second)
}

@Singleton
class NoCacheFilter @Inject()(
                               implicit override val mat: Materializer,
                               exec: ExecutionContext) extends Filter {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val rootRegex: Regex = "/v2/.{3}/live".r

  override def apply(requestHeaderToFutureResult: (RequestHeader) => Future[Result])(rh: RequestHeader): Future[Result] = {
    requestHeaderToFutureResult(rh).map { result =>
      rh.uri match {
        case rootRegex() =>
          result.withHeaders(HeaderNames.CACHE_CONTROL -> "no-cache")
        case _ =>
          result
      }
    }
  }
}

trait AvailableUserRoles {
  val availableRoles = List("staff:edit")
}

class Application @Inject()(implicit val config: Configuration,
                            implicit val mat: Materializer,
                            env: Environment,
                            override val system: ActorSystem,
                            ec: ExecutionContext)
  extends Controller
    with AirportConfProvider
    with ProdPassengerSplitProviders
    with SystemActors
    with ImplicitTimeoutProvider
    with AvailableUserRoles {
  ctrl =>
  val log: LoggingAdapter = system.log

  log.info(s"Starting DRTv2 build ${BuildInfo.version}")

  log.info(s"ISOChronology.getInstance: ${ISOChronology.getInstance}")
  private val systemTimeZone = System.getProperty("user.timezone")
  log.info(s"System.getProperty(user.timezone): $systemTimeZone")
  assert(systemTimeZone == "UTC")

  log.info(s"Application using airportConfig $airportConfig")

  val cacheActorRef: AskableActorRef = system.actorOf(Props(classOf[CachingCrunchReadActor]), name = "cache-actor")

  def previousDay(date: MilliDate): SDateLike = {
    val oneDayInMillis = 60 * 60 * 24 * 1000L
    SDate(date.millisSinceEpoch - oneDayInMillis)
  }

  object ApiService {
    def apply(
               airportConfig: AirportConfig,
               shiftsActor: ActorRef,
               fixedPointsActor: ActorRef,
               staffMovementsActor: ActorRef,
               headers: Headers
             ): ApiService = new ApiService(airportConfig, shiftsActor, fixedPointsActor, staffMovementsActor, headers) {

      override implicit val timeout: Timeout = Timeout(5 seconds)

      def actorSystem: ActorSystem = system

      def getCrunchStateForDay(day: MillisSinceEpoch): Future[Option[CrunchState]] = loadBestCrunchStateForPointInTime(day)

      def getApplicationVersion(): String = BuildInfo.version

      override def getCrunchStateForPointInTime(pointInTime: MillisSinceEpoch): Future[Option[CrunchState]] = {
        crunchStateAtPointInTime(pointInTime)
      }

      def getCrunchUpdates(sinceMillis: MillisSinceEpoch): Future[Option[CrunchUpdates]] = {
        val startMillis = midnightThisMorning
        val endMillis = midnightThisMorning + oneHourMillis * 24
        val crunchStateFuture = liveCrunchStateActor.ask(GetUpdatesSince(sinceMillis, startMillis, endMillis))(new Timeout(30 seconds))

        crunchStateFuture.map {
          case Some(cu: CrunchUpdates) => Option(cu)
          case _ => None
        } recover {
          case t =>
            log.warn(s"Didn't get a CrunchUpdates: $t")
            None
        }
      }

      def isLoggedIn(): Boolean = {
        true
      }

      def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: TerminalName): Future[Option[ForecastPeriodWithHeadlines]] = {
        val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay))
        val endOfForecast = startOfWeekMidnight.addDays(7).millisSinceEpoch
        val now = SDate.now()

        val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) {
          log.info(s"${startOfWeekMidnight.toLocalDateTimeString()} < ${now.toLocalDateTimeString()}, going to use ${getLocalNextMidnight(now)} instead")
          getLocalNextMidnight(now)
        } else startOfWeekMidnight

        val crunchStateFuture = forecastCrunchStateActor.ask(
          GetPortState(startOfForecast.millisSinceEpoch, endOfForecast)
        )(new Timeout(30 seconds))

        crunchStateFuture.map {
          case Some(PortState(_, m, s)) =>
            log.info(s"Sent forecast for week beginning ${SDate(startDay).toISOString()} on $terminal")
            val timeSlotsByDay = Forecast.rollUpForWeek(m.values.toSet, s.values.toSet, terminal)
            val period = ForecastPeriod(timeSlotsByDay)
            val headlineFigures = Forecast.headLineFigures(m.values.toSet, terminal)
            Option(ForecastPeriodWithHeadlines(period, headlineFigures))
          case None =>
            log.info(s"No forecast available for week beginning ${SDate(startDay).toISOString()} on $terminal")
            None
        }
      }

      def forecastWeekHeadlineFigures(startDay: MillisSinceEpoch, terminal: TerminalName): Future[Option[ForecastHeadlineFigures]] = {
        val midnight = getLocalLastMidnight(SDate(startDay))
        val crunchStateFuture = forecastCrunchStateActor.ask(
          GetPortState(midnight.millisSinceEpoch, midnight.addDays(7).millisSinceEpoch)
        )(new Timeout(30 seconds))

        crunchStateFuture.map {
          case Some(PortState(_, m, _)) =>

            Option(Forecast.headLineFigures(m.values.toSet, terminal))
          case None =>
            log.info(s"No forecast available for week beginning ${SDate(startDay).toISOString()} on $terminal")
            None
        }
      }

      def saveStaffTimeSlotsForMonth(timeSlotsForTerminalMonth: StaffTimeSlotsForTerminalMonth): Future[Unit] = {
        if (getUserRoles().contains("staff:edit")) {
          log.info(s"Saving ${timeSlotsForTerminalMonth.timeSlots.length} timeslots for ${SDate(timeSlotsForTerminalMonth.monthMillis).ddMMyyString}")
          val futureShifts = shiftsActor.ask(GetState)(new Timeout(5 second))
          futureShifts.map {
            case shifts: String =>
              val updatedShifts = StaffTimeSlots.replaceShiftMonthWithTimeSlotsForMonth(shifts, timeSlotsForTerminalMonth)

              shiftsActor ! updatedShifts
          }
        } else throw new Exception("You do not have permission to edit staffing.")
      }

      def getShiftsForMonth(month: MillisSinceEpoch, terminalName: TerminalName): Future[String] = {
        val shiftsFuture = shiftsActor ? GetState

        shiftsFuture.collect {
          case shifts: String =>
            log.info(s"Shifts: Retrieved shifts from actor")
            StaffTimeSlots.getShiftsForMonth(shifts, SDate(month), terminalName)
        }
      }

      def getUserRoles(): List[String] = if (config.getString("feature-flags.super-user-mode").isDefined)
        availableRoles
      else
        roles

      override def askableCacheActorRef: AskableActorRef = cacheActorRef

      override def liveCrunchStateActor: AskableActorRef = ctrl.liveCrunchStateActor

      override def forecastCrunchStateActor: AskableActorRef = ctrl.forecastCrunchStateActor

    }
  }

  def loadBestCrunchStateForPointInTime(day: MillisSinceEpoch): Future[Option[CrunchState]] =
    if (isHistoricDate(day)) {
      crunchStateForEndOfDay(day)
    } else if (day <= getLocalNextMidnight(SDate.now()).millisSinceEpoch) {
      ctrl.liveCrunchStateActor.ask(GetState).map {
        case Some(PortState(f, m, s)) => Option(CrunchState(f.values.toSet, m.values.toSet, s.values.toSet))
        case _ => None
      }
    } else {
      crunchStateForDayInForecast(day)
    }

  def crunchStateForDayInForecast(day: MillisSinceEpoch): Future[Option[CrunchState]] = {
    val firstMinute = getLocalLastMidnight(SDate(day)).millisSinceEpoch
    val lastMinute = SDate(firstMinute).addDays(1).millisSinceEpoch

    val crunchStateFuture = forecastCrunchStateActor.ask(GetPortState(firstMinute, lastMinute))(new Timeout(30 seconds))

    crunchStateFuture.map {
      case Some(PortState(f, m, s)) => Option(CrunchState(f.values.toSet, m.values.toSet, s.values.toSet))
      case _ => None
    } recover {
      case t =>
        log.warning(s"Didn't get a CrunchState: $t")
        None
    }
  }

  def isHistoricDate(day: MillisSinceEpoch): Boolean = {
    day < getLocalLastMidnight(SDate.now()).millisSinceEpoch
  }

  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }


  def crunchStateAtPointInTime(pointInTime: MillisSinceEpoch): Future[Option[CrunchState]] = {
    val relativeLastMidnight = getLocalLastMidnight(SDate(pointInTime)).millisSinceEpoch
    val startMillis = relativeLastMidnight
    val endMillis = relativeLastMidnight + oneHourMillis * 24

    portStatePeriodAtPointInTime(startMillis, endMillis, pointInTime)
  }

  def crunchStateForEndOfDay(day: MillisSinceEpoch): Future[Option[CrunchState]] = {
    val relativeLastMidnight = getLocalLastMidnight(SDate(day)).millisSinceEpoch
    val startMillis = relativeLastMidnight
    val endMillis = relativeLastMidnight + oneHourMillis * 24
    val pointInTime = endMillis + oneHourMillis * 3

    portStatePeriodAtPointInTime(startMillis, endMillis, pointInTime)
  }

  def portStatePeriodAtPointInTime(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, pointInTime: MillisSinceEpoch): Future[Option[CrunchState]] = {
    val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], airportConfig.portStateSnapshotInterval, SDate(pointInTime), airportConfig.queues), GetPortState(startMillis, endMillis))
    val portCrunchResult = cacheActorRef.ask(query)(new Timeout(30 seconds))
    portCrunchResult.map {
      case Some(PortState(f, m, s)) => Option(CrunchState(f.values.toSet, m.values.toSet, s.values.toSet))
      case _ => None
    }.recover {
      case t =>
        log.warning(s"Didn't get a point-in-time CrunchState: $t")
        None
    }
  }

  def exportDesksAndQueuesAtPointInTimeCSV(
                                            pointInTime: String,
                                            terminalName: TerminalName,
                                            startHour: Int,
                                            endHour: Int
                                          ): Action[AnyContent] = Action.async {

    log.info(s"Exports: For point in time ${SDate(pointInTime.toLong).toISOString()}")
    val portCode = airportConfig.portCode
    val pit = MilliDate(pointInTime.toLong)

    val fileName = f"$portCode-$terminalName-desks-and-queues-${pit.getFullYear()}-${pit.getMonth()}%02d-${pit.getDate()}%02dT" +
      f"${pit.getHours()}%02d-${pit.getMinutes()}%02d-hours-$startHour%02d-to-$endHour%02d"

    val crunchStateForPointInTime = loadBestCrunchStateForPointInTime(pit.millisSinceEpoch)
    exportDesksToCSV(terminalName, pit, startHour, endHour, crunchStateForPointInTime).map {
      case Some(csvData) =>
        val columnHeadings = CSVData.terminalCrunchMinutesToCsvDataHeadings(airportConfig.queues(terminalName))
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(columnHeadings + CSVData.lineEnding + csvData), Option("application/csv")))
      case None =>
        NotFound("Could not find desks and queues for this date.")
    }
  }

  def exportDesksToCSV(
                        terminalName: TerminalName,
                        pointInTime: MilliDate,
                        startHour: Int,
                        endHour: Int,
                        crunchStateFuture: Future[Option[CrunchState]]
                      ): Future[Option[String]] = {

    val startDateTime = getLocalLastMidnight(pointInTime).addHours(startHour)
    val endDateTime = getLocalLastMidnight(pointInTime).addHours(endHour)
    val isInRange = isInRangeOnDay(startDateTime, endDateTime) _

    val localTime = SDate(pointInTime, europeLondonTimeZone)
    crunchStateFuture.map {
      case Some(CrunchState(_, cm, sm)) =>
        log.debug(s"Exports: ${localTime.toISOString()} Got ${cm.size} CMs and ${sm.size} SMs ")
        val cmForDay: Set[CrunchMinute] = cm.filter(cm => isInRange(SDate(cm.minute, europeLondonTimeZone)))
        val smForDay: Set[StaffMinute] = sm.filter(sm => isInRange(SDate(sm.minute, europeLondonTimeZone)))
        log.debug(s"Exports: ${localTime.toISOString()} filtered to ${cmForDay.size} CMs and ${smForDay.size} SMs ")
        Option(CSVData.terminalCrunchMinutesToCsvData(cmForDay, smForDay, terminalName, airportConfig.queues(terminalName)))
      case unexpected =>
        log.error(s"Exports: Got the wrong thing $unexpected for Point In time: ${localTime.toISOString()}")

        None
    }
  }

  def exportForecastWeekToCSV(startDay: String, terminal: TerminalName): Action[AnyContent] = Action.async {
    val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay.toLong))
    val endOfForecast = startOfWeekMidnight.addDays(180)
    val now = SDate.now()

    val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) {
      log.info(s"${startOfWeekMidnight.toLocalDateTimeString()} < ${now.toLocalDateTimeString()}, going to use ${getLocalNextMidnight(now)} instead")
      getLocalNextMidnight(now)
    } else startOfWeekMidnight

    val crunchStateFuture = forecastCrunchStateActor.ask(
      GetPortState(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch)
    )(new Timeout(30 seconds))

    val portCode = airportConfig.portCode

    val fileName = f"$portCode-$terminal-forecast-export-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
    crunchStateFuture.map {
      case Some(PortState(_, m, s)) =>
        log.info(s"Forecast CSV export for $terminal on $startDay with: crunch minutes: ${m.size} staff minutes: ${s.size}")
        val csvData = CSVData.forecastPeriodToCsv(ForecastPeriod(Forecast.rollUpForWeek(m.values.toSet, s.values.toSet, terminal)))
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
        )

      case None =>
        log.error(s"Forecast CSV Export: Missing planning data for ${startOfWeekMidnight.ddMMyyString} for Terminal $terminal")
        NotFound(s"Sorry, no planning summary available for week starting ${startOfWeekMidnight.ddMMyyString}")
    }
  }

  def exportForecastWeekHeadlinesToCSV(startDay: String, terminal: TerminalName): Action[AnyContent] = Action.async {
    val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay.toLong))
    val endOfForecast = startOfWeekMidnight.addDays(180)
    val now = SDate.now()

    val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) {
      log.info(s"${startOfWeekMidnight.toLocalDateTimeString()} < ${now.toLocalDateTimeString()}, going to use ${getLocalNextMidnight(now)} instead")
      getLocalNextMidnight(now)
    } else startOfWeekMidnight

    val crunchStateFuture = forecastCrunchStateActor.ask(
      GetPortState(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch)
    )(new Timeout(30 seconds))


    val fileName = f"${airportConfig.portCode}-$terminal-forecast-export-headlines-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
    crunchStateFuture.map {
      case Some(PortState(_, m, _)) =>
        val csvData = CSVData.forecastHeadlineToCSV(Forecast.headLineFigures(m.values.toSet, terminal), airportConfig.exportQueueOrder)
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv")
          )
        )

      case None =>
        log.error(s"Missing headline data for ${startOfWeekMidnight.ddMMyyString} for Terminal $terminal")
        NotFound(s"Sorry, no headlines available for week starting ${startOfWeekMidnight.ddMMyyString}")
    }
  }

  def exportFlightsWithSplitsAtPointInTimeCSV(pointInTime: String, terminalName: TerminalName, startHour: Int, endHour: Int): Action[AnyContent] = Action.async {
    val pit = MilliDate(pointInTime.toLong)

    val portCode = airportConfig.portCode
    val fileName = f"$portCode-$terminalName-arrivals-${pit.getFullYear()}-${pit.getMonth()}%02d-${pit.getDate()}%02dT" +
      f"${pit.getHours()}%02d-${pit.getMinutes()}%02d-hours-$startHour%02d-to-$endHour%02d"

    val crunchStateForPointInTime = loadBestCrunchStateForPointInTime(pit.millisSinceEpoch)
    flightsForCSVExportWithinRange(terminalName, pit, startHour, endHour, crunchStateForPointInTime).map {
      case Some(csvFlights) =>
        val csvData = CSVData.flightsWithSplitsToCSVWithHeadings(csvFlights)
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
        )
      case None => NotFound("No data for this date")
    }
  }

  def exportFlightsWithSplitsBetweenTimeStampsCSV(start: String, end: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    val startPit = getLocalLastMidnight(SDate(start.toLong, europeLondonTimeZone))
    val endPit = SDate(end.toLong, europeLondonTimeZone)

    val portCode = airportConfig.portCode
    val fileName = makeFileName("arrivals", terminalName, startPit, endPit, portCode)

    val dayRangeInMillis = startPit.millisSinceEpoch to endPit.millisSinceEpoch by oneDayMillis
    val days: Seq[Future[Option[String]]] = dayRangeInMillis.zipWithIndex.map {

      case (dayMillis, index) =>
        val csvFunc = if (index == 0) CSVData.flightsWithSplitsToCSVWithHeadings _ else CSVData.flightsWithSplitsToCSV _
        flightsForCSVExportWithinRange(
          terminalName = terminalName,
          pit = MilliDate(dayMillis),
          startHour = 0,
          endHour = 24,
          crunchStateFuture = loadBestCrunchStateForPointInTime(dayMillis)
        ).map {
          case Some(fs) => Option(csvFunc(fs))
          case None =>
            log.error(s"Missing a day of flights")
            None
        }
    }

    CSVData.multiDayToSingleExport(days).map(csvData => {
      Result(ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
        HttpEntity.Strict(ByteString(csvData), Option("application/csv")))
    })
  }

  def exportDesksAndQueuesBetweenTimeStampsCSV(start: String, end: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    val startPit = getLocalLastMidnight(SDate(start.toLong, europeLondonTimeZone))
    val endPit = SDate(end.toLong, europeLondonTimeZone)

    val portCode = airportConfig.portCode
    val fileName = makeFileName("desks-and-queues", terminalName, startPit, endPit, portCode)

    val dayRangeMillis = startPit.millisSinceEpoch to endPit.millisSinceEpoch by oneDayMillis
    val days: Seq[Future[Option[String]]] = dayRangeMillis.map(
      millis => exportDesksToCSV(
        terminalName = terminalName,
        pointInTime = MilliDate(millis),
        startHour = 0,
        endHour = 24,
        crunchStateFuture = loadBestCrunchStateForPointInTime(millis)
      )
    )

    CSVData.multiDayToSingleExport(days).map(csvData => {
      Result(ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
        HttpEntity.Strict(ByteString(
          CSVData.terminalCrunchMinutesToCsvDataHeadings(airportConfig.queues(terminalName)) + CSVData.lineEnding + csvData
        ), Option("application/csv")))
    })
  }

  def makeFileName(subject: String, terminalName: TerminalName, startPit: SDateLike, endPit: SDateLike, portCode: String): String = {
    f"$portCode-$terminalName-$subject-" +
      f"${startPit.getFullYear()}-${startPit.getMonth()}%02d-${startPit.getDate()}-to-" +
      f"${endPit.getFullYear()}-${endPit.getMonth()}%02d-${endPit.getDate()}"
  }

  def fetchAclFeed(portCode: String): Action[AnyContent] = Action.async {
    val fileName = AclFeed.latestFileForPort(aclFeed.sftp, portCode.toUpperCase)

    log.info(s"Latest ACL file for $portCode: $fileName. Fetching..")

    val zipContent = AclFeed.contentFromFileName(aclFeed.sftp, fileName)
    val csvFileName = fileName.replace(".zip", ".csv")

    val result = Result(
      ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$csvFileName'")),
      HttpEntity.Strict(ByteString(zipContent), Option("application/csv"))
    )

    Future(result)
  }

  def isInRangeOnDay(startDateTime: SDateLike, endDateTime: SDateLike)(minute: SDateLike): Boolean =
    startDateTime.millisSinceEpoch <= minute.millisSinceEpoch && minute.millisSinceEpoch < endDateTime.millisSinceEpoch


  def flightsForCSVExportWithinRange(
                                      terminalName: TerminalName,
                                      pit: MilliDate,
                                      startHour: Int,
                                      endHour: Int,
                                      crunchStateFuture: Future[Option[CrunchState]]
                                    ): Future[Option[List[ApiFlightWithSplits]]] = {

    val startDateTime = getLocalLastMidnight(pit).addHours(startHour)
    val endDateTime = getLocalLastMidnight(pit).addHours(endHour)
    val isInRange = isInRangeOnDay(startDateTime, endDateTime) _

    crunchStateFuture.map {
      case Some(CrunchState(fs, _, _)) =>

        val flightsForTerminalInRange = fs.toList
          .filter(_.apiFlight.Terminal == terminalName)
          .filter(f => isInRange(SDate(f.apiFlight.PcpTime, europeLondonTimeZone)))

        Option(flightsForTerminalInRange)
      case unexpected =>
        log.error(s"got the wrong thing extracting flights from CrunchState (terminal: $terminalName, millis: $pit," +
          s" start hour: $startHour, endHour: $endHour): Error: $unexpected")
        None
    }
  }

  def saveStaff() = Action {
    implicit request =>
      val maybeShifts: Option[String] = request.body.asJson.flatMap(ImportStaff.staffJsonToShifts)

      maybeShifts match {
        case Some(shiftsString) =>
          log.info(s"Received ${shiftsString.split("\n").length} shifts. Sending to actor")
          shiftsActor ! shiftsString
          Created
        case _ =>
          BadRequest("{\"error\": \"Unable to parse data\"}")
      }
  }

  def autowireApi(path: String): Action[RawBuffer] = Action.async(parse.raw) {
    implicit request =>
      log.info(s"Request path: $path")

      // get the request body as ByteString
      val b = request.body.asBytes(parse.UNLIMITED).get

      // call Autowire route

      implicit val pickler = generatePickler[ApiPaxTypeAndQueueCount]
      val router = Router.route[Api](ApiService(airportConfig, shiftsActor, fixedPointsActor, staffMovementsActor, request.headers))

      router(
        autowire.Core.Request(path.split("/"), Unpickle[Map[String, ByteBuffer]].fromBytes(b.asByteBuffer))
      ).map(buffer => {
        val data = Array.ofDim[Byte](buffer.remaining())
        buffer.get(data)
        Ok(data)
      })
  }

  def logging: Action[AnyContent] = Action(parse.anyContent) {
    implicit request =>
      request.body.asJson.foreach {
        msg =>
          log.info(s"CLIENT - $msg")
      }
      Ok("")
  }
}

object Forecast {
  def headLineFigures(forecastMinutes: Set[CrunchMinute], terminalName: TerminalName): ForecastHeadlineFigures = {
    val headlines = forecastMinutes
      .toList
      .filter(_.terminalName == terminalName)
      .groupBy(
        cm => getLocalLastMidnight(SDate(cm.minute)).millisSinceEpoch
      )
      .flatMap {
        case (day, cm) =>
          cm.groupBy(_.queueName)
            .map {
              case (q, cms) =>
                QueueHeadline(
                  day,
                  q,
                  Math.round(cms.map(_.paxLoad).sum).toInt,
                  Math.round(cms.map(_.workLoad).sum).toInt
                )
            }
      }.toSet
    ForecastHeadlineFigures(headlines)
  }

  def rollUpForWeek(forecastMinutes: Set[CrunchMinute], staffMinutes: Set[StaffMinute], terminalName: TerminalName): Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = {
    val actualStaffByMinute = staffByTimeSlot(15)(staffMinutes, terminalName)
    val fixedPointsByMinute = fixedPointsByTimeSlot(15)(staffMinutes, terminalName)
    val terminalMinutes = CrunchApi.terminalMinutesByMinute(forecastMinutes, terminalName)
    groupCrunchMinutesByX(15)(terminalMinutes, terminalName, Queues.queueOrder)
      .map {
        case (startMillis, cms) =>
          val available = actualStaffByMinute.getOrElse(startMillis, 0)
          val fixedPoints = fixedPointsByMinute.getOrElse(startMillis, 0)
          val forecastTimeSlot = ForecastTimeSlot(startMillis, available, required = fixedPoints)
          cms.foldLeft(forecastTimeSlot) {
            case (fts, cm) => fts.copy(required = fts.required + cm.deskRec)
          }
      }
      .groupBy(forecastTimeSlot => getLocalLastMidnight(SDate(forecastTimeSlot.startMillis)).millisSinceEpoch)
  }
}

case class GetTerminalCrunch(terminalName: TerminalName)
