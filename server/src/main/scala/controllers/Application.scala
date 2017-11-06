package controllers

import java.nio.ByteBuffer

import actors._
import actors.pointInTime.CrunchStateReadActor
import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern.{AskableActorRef, _}
import akka.stream._
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import controllers.SystemActors.SplitsProvider
import drt.chroma.chromafetcher.ChromaFetcherLive.ChromaSingleFlight
import drt.chroma.{FeedType, ForecastFeed, LiveFeed}
import drt.server.feeds.chroma.{ChromaLiveFeed, ProdChroma}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{Flights, TerminalName}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{AirportConfig, Api, Arrival, _}
import drt.staff.ImportStaff
import org.joda.time.chrono.ISOChronology
import org.slf4j.{Logger, LoggerFactory}
import play.api.http.HttpEntity
import play.api.mvc._
import play.api.{Configuration, Environment}
import server.feeds.acl.AclFeed
import services.PcpArrival._
import services.SDate.implicits._
import services.SplitsProvider.SplitProvider
import services.crunch.CrunchSystem
import services.crunch.CrunchSystem.CrunchProps
import services.graphstages.Crunch._
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.PaxTypeAndQueueCount
import services.{SDate, _}

import scala.collection.immutable
import scala.collection.immutable.{IndexedSeq, Map}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
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

object SystemActors {
  type SplitsProvider = (Arrival) => Option[SplitRatios]
}

trait SystemActors {
  self: AirportConfProvider =>

  implicit val system: ActorSystem

  val config: Configuration

  val maxDaysToCrunch: Int = ConfigFactory.load.getString("crunch.forecast.max_days").toInt
  val aclPollMinutes: Int = ConfigFactory.load.getString("crunch.forecast.poll_minutes").toInt
  val expireAfterMillis: Long = 2 * oneDayMillis
  val now: () => SDateLike = () => SDate.now()

  val ftpServer: String = ConfigFactory.load.getString("acl.host")
  val username: String = ConfigFactory.load.getString("acl.username")
  val path: String = ConfigFactory.load.getString("acl.keypath")

  val aclFeed = AclFeed(ftpServer, username, path, airportConfig.portCode)

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))
  val actorMaterializer = ActorMaterializer()

  val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor], now, expireAfterMillis), name = "voyage-manifests-actor")
  val liveCrunchStateActor: ActorRef = system.actorOf(Props(classOf[CrunchStateActor], airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queues, now, expireAfterMillis), name = "crunch-live-state-actor")
  val forecastCrunchStateActor: ActorRef = system.actorOf(Props(classOf[CrunchStateActor], 100, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis), name = "crunch-forecast-state-actor")
  val historicalSplitsProvider: SplitsProvider = SplitsProvider.csvProvider

  val crunchInputs: CrunchSystem = CrunchSystem(CrunchProps(
    system = system,
    airportConfig = airportConfig,
    pcpArrival = pcpArrivalTimeCalculator,
    historicalSplitsProvider = historicalSplitsProvider,
    liveCrunchStateActor = liveCrunchStateActor,
    forecastCrunchStateActor = forecastCrunchStateActor,
    maxDaysToCrunch = maxDaysToCrunch,
    expireAfterMillis = expireAfterMillis))

  val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor], crunchInputs.shifts))
  val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor], crunchInputs.fixedPoints))
  val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor], crunchInputs.staffMovements))

  liveArrivalsSource(airportConfig.portCode)
    .runForeach(f => crunchInputs.liveArrivals.offer(f))(actorMaterializer)
  forecastArrivalsSource(airportConfig.portCode)
    .runForeach(f => crunchInputs.forecastArrivals.offer(f))(actorMaterializer)

  system.scheduler.schedule(0 milliseconds, aclPollMinutes minutes) {
    crunchInputs.baseArrivals.offer(aclFeed.arrivals)
  }

  if (portCode == "LHR") config.getString("lhr.blackjack_url").map(csvUrl => {
    val threeMinutesInterval = 3 * 60 * 1000
    Deskstats.startBlackjack(csvUrl, crunchInputs.actualDeskStats, threeMinutesInterval milliseconds, SDate.now().addDays(-1))
  })

  val bucket: String = config.getString("dq.s3.bucket").getOrElse(throw new Exception("You must set DQ_S3_BUCKET for us to poll for AdvPaxInfo"))

  VoyageManifestsProvider(bucket, airportConfig.portCode, crunchInputs.manifests, voyageManifestsActor).start()

  def liveArrivalsSource(portCode: String): Source[Flights, Cancellable] = {
    val feed = portCode match {
      case "LHR" => LHRFlightFeed()
      case "EDI" => createChromaFlightFeed(LiveFeed).chromaEdiFlights()
      case _ => createChromaFlightFeed(LiveFeed).chromaVanillaFlights(30 seconds)
    }
    feed.map(Flights)
  }

  def forecastArrivalsSource(portCode: String): Source[Flights, Cancellable] = {
    val feed = portCode match {
      case _ => createChromaFlightFeed(ForecastFeed).chromaVanillaFlights(30 minutes)
    }
    feed.map(Flights)
  }

  def walkTimeProvider(flight: Arrival): MillisSinceEpoch =
    gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, airportConfig.defaultWalkTimeMillis)(flight)

  def pcpArrivalTimeCalculator: (Arrival) => MilliDate =
    PaxFlow.pcpArrivalTimeForFlight(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis)(walkTimeProvider)

  def createChromaFlightFeed(feedType: FeedType): ChromaLiveFeed = {
    system.log.info(s"feedType: $feedType")
    val fetcher = ProdChroma[ChromaSingleFlight](system, feedType)
    ChromaLiveFeed(system.log, fetcher)
  }
}

trait AirportConfiguration {
  def airportConfig: AirportConfig
}

trait AirportConfProvider extends AirportConfiguration {
  val portCode: String = ConfigFactory.load().getString("portcode").toUpperCase

  def mockProd: String = sys.env.getOrElse("MOCK_PROD", "PROD").toUpperCase

  def getPortConfFromEnvVar: AirportConfig = AirportConfigs.confByPort(portCode)

  def airportConfig: AirportConfig = getPortConfFromEnvVar
}

trait ProdPassengerSplitProviders {
  self: AirportConfiguration with SystemActors =>

  val csvSplitsProvider: (Arrival) => Option[SplitRatios] = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: Arrival): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: Arrival): Option[FastTrackPercentages] =
    Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight), 0d, 0d))

  private implicit val timeout: Timeout = Timeout(250 milliseconds)
}

trait ImplicitTimeoutProvider {
  implicit val timeout: Timeout = Timeout(1 second)
}

class Application @Inject()(implicit val config: Configuration,
                            implicit val mat: Materializer,
                            env: Environment,
                            override val system: ActorSystem,
                            ec: ExecutionContext)
  extends Controller
    with AirportConfProvider
    with ProdPassengerSplitProviders
    with SystemActors with ImplicitTimeoutProvider {
  ctrl =>
  val log: LoggingAdapter = system.log

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
    def apply(airportConfig: AirportConfig, shiftsActor: ActorRef, fixedPointsActor: ActorRef, staffMovementsActor: ActorRef): ApiService {
      val timeout: Timeout

      def liveCrunchStateActor: AskableActorRef

      def actorSystem: ActorSystem

      def forecastCrunchStateActor: AskableActorRef

      def getCrunchUpdates(sinceMillis: MillisSinceEpoch): Future[Option[CrunchUpdates]]

      def askableCacheActorRef: AskableActorRef

      def getCrunchStateForDay(day: MillisSinceEpoch): Future[Option[CrunchState]]

      def getCrunchStateForPointInTime(pointInTime: MillisSinceEpoch): Future[Option[CrunchState]]
    } = new ApiService(airportConfig, shiftsActor, fixedPointsActor, staffMovementsActor) {

      override implicit val timeout: Timeout = Timeout(5 seconds)

      def actorSystem: ActorSystem = system

      def getCrunchStateForDay(day: MillisSinceEpoch): Future[Option[CrunchState]] = {
        if (isHistoricDate(day)) {
          crunchStateForEndOfDay(day)
        } else if (day < getLocalNextMidnight(SDate.now()).millisSinceEpoch) {
          log.error(s"Trying to load live CrunchState from Forecast Actor.")
          Future(None)
        } else {
          crunchStateForDayInForecast(day)
        }
      }

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

      def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: TerminalName): Future[Option[ForecastPeriodWithHeadlines]] = {

        val midnight = getLocalLastMidnight(SDate(startDay))
        val crunchStateFuture = forecastCrunchStateActor.ask(
          GetPortState(midnight.millisSinceEpoch, midnight.addDays(7).millisSinceEpoch)
        )(new Timeout(30 seconds))

        crunchStateFuture.map {
          case Some(PortState(_, m, s)) =>
            log.info(s"Sent forecast for week beginning ${SDate(startDay).toISOString()} on $terminal")
            Option(
              ForecastPeriodWithHeadlines(
                ForecastPeriod(Forecast.rollUpForWeek(m.values.toSet, s.values.toSet, terminal)),
                Forecast.headLineFigures(m.values.toSet, terminal)
              )
            )
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

      override def askableCacheActorRef: AskableActorRef = cacheActorRef

      override def liveCrunchStateActor: AskableActorRef = ctrl.liveCrunchStateActor

      override def forecastCrunchStateActor: AskableActorRef = ctrl.forecastCrunchStateActor

    }
  }

  def crunchStateForDayInForecast(day: MillisSinceEpoch): Future[Option[CrunchState]] = {
    val firstMinute = getLocalLastMidnight(SDate(day)).millisSinceEpoch
    val lastMinute = getLocalNextMidnight(SDate(day)).millisSinceEpoch

    val crunchStateFuture = forecastCrunchStateActor.ask(GetPortState(firstMinute, lastMinute))(new Timeout(30 seconds))

    crunchStateFuture.map {
      case Some(PortState(f, m, _)) => Option(CrunchState(0L, 0, f.values.toSet, m.values.toSet))
      case _ => None
    } recover {
      case t =>
        log.warning(s"Didn't get a CrunchState: $t")
        None
    }
  }

  def isHistoricDate(day: MillisSinceEpoch): Boolean = {
    day < getLocalNextMidnight(SDate.now()).millisSinceEpoch
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
      case Some(PortState(f, m, _)) => Option(CrunchState(0L, 0, f.values.toSet, m.values.toSet))
      case _ => None
    }.recover {
      case t =>
        log.warning(s"Didn't get a point-in-time CrunchState: $t")
        None
    }
  }

  def getDesksAndQueuesCSVAtPointInTime(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {

    val crunchStateFuture: Future[Option[CrunchState]] = crunchStateAtPointInTime(pointInTime.toLong)

    desksToCSV(pointInTime, terminalName, crunchStateFuture)
  }

  def getDesksAndQueuesForDayCSV(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {

    val crunchStateFuture: Future[Option[CrunchState]] = crunchStateForEndOfDay(pointInTime.toLong)

    desksToCSV(pointInTime, terminalName, crunchStateFuture)
  }

  def desksToCSV(pointInTime: String, terminalName: TerminalName, crunchStateFuture: Future[Option[CrunchState]]): Future[Result] = {
    val pitMilliDate = MilliDate(pointInTime.toLong)

    val fileName = s"$terminalName-desks-and-queues-${pitMilliDate.getFullYear()}-${pitMilliDate.getMonth()}-${pitMilliDate.getDate()}T${pitMilliDate.getHours()}-${pitMilliDate.getMinutes()}"

    crunchStateFuture.map {
      case Some(CrunchState(_, _, _, cm)) =>
        val cmForDay = cm.filter(cm => MilliDate(cm.minute).ddMMyyString == pitMilliDate.ddMMyyString)
        val csvData = CSVData.terminalCrunchMinutesToCsvData(cmForDay, terminalName, airportConfig.queues(terminalName))
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
        )
      case unexpected =>
        log.error(s"got the wrong thing: $unexpected")
        NotFound("")
    }
  }

  def getForecastWeekToCSV(startDay: String, terminal: TerminalName): Action[AnyContent] = Action.async {

    val startDayMidnight = getLocalLastMidnight(SDate(startDay.toLong))
    val crunchStateFuture = forecastCrunchStateActor.ask(
      GetPortState(startDayMidnight.millisSinceEpoch, startDayMidnight.addDays(180).millisSinceEpoch)
    )(new Timeout(30 seconds))

    val fileName = s"$terminal-planning-${startDayMidnight.getFullYear()}-${startDayMidnight.getMonth()}-${startDayMidnight.getDate()}"
    crunchStateFuture.map {
      case Some(PortState(_, m, s)) =>
        val csvData = CSVData.forecastPeriodToCsv(ForecastPeriod(Forecast.rollUpForWeek(m.values.toSet, s.values.toSet, terminal)))
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv")
          )
        )

      case None =>
        log.error(s"Missing planning data for ${startDayMidnight.ddMMyyString} for Terminal $terminal")
        NotFound(s"Sorry, no planning summary available for week starting ${startDayMidnight.ddMMyyString}")
    }
  }

  def getForecastWeekHeadlinesToCSV(startDay: String, terminal: TerminalName): Action[AnyContent] = Action.async {

    val startDayMidnight = getLocalLastMidnight(SDate(startDay.toLong))
    val crunchStateFuture = forecastCrunchStateActor.ask(
      GetPortState(startDayMidnight.millisSinceEpoch, startDayMidnight.addDays(180).millisSinceEpoch)
    )(new Timeout(30 seconds))

    val fileName = s"$terminal-headlines-${startDayMidnight.getFullYear()}-${startDayMidnight.getMonth()}-${startDayMidnight.getDate()}"
    crunchStateFuture.map {
      case Some(PortState(_, m, _)) =>
        val csvData = CSVData.forecastHeadlineToCSV(Forecast.headLineFigures(m.values.toSet, terminal))
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv")
          )
        )

      case None =>
        log.error(s"Missing headline data for ${startDayMidnight.ddMMyyString} for Terminal $terminal")
        NotFound(s"Sorry, no headlines available for week starting ${startDayMidnight.ddMMyyString}")
    }
  }

  def getFlightsWithSplitsAtPointInTimeCSV(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    val potMilliDate = MilliDate(pointInTime.toLong)
    val crunchStateFuture = crunchStateAtPointInTime(pointInTime.toLong)

    flightsCSVFromCrunchState(terminalName, potMilliDate, crunchStateFuture)
  }

  def getFlightsWithSplitsForDayCSV(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    val potMilliDate = MilliDate(pointInTime.toLong)
    val crunchStateFuture = crunchStateForEndOfDay(pointInTime.toLong)

    flightsCSVFromCrunchState(terminalName, potMilliDate, crunchStateFuture)
  }

  def flightsCSVFromCrunchState(terminalName: TerminalName, potMilliDate: MilliDate, crunchStateFuture: Future[Option[CrunchState]]): Future[Result] = {
    val fileName = s"$terminalName-arrivals-${potMilliDate.getFullYear()}-${potMilliDate.getMonth()}-${potMilliDate.getDate()}T${potMilliDate.getHours()}-${potMilliDate.getMinutes()}"

    crunchStateFuture.map {
      case Some(CrunchState(_, _, fs, _)) =>
        val csvData = CSVData.flightsWithSplitsToCSV(fs.toList.filter(_.apiFlight.Terminal == terminalName))
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
        )
      case unexpected =>
        log.error(s"got the wrong thing: $unexpected")
        NotFound("")
    }
  }

  def saveStaff() = Action {
    implicit request =>
      request.body.asJson.flatMap(ImportStaff.staffJsonToShifts) match {
        case Some(shiftsString) =>
          shiftsActor ! shiftsString
          Created
        case _ =>
          BadRequest("{\"error\": \"Unable to parse data\"}")
      }
  }

  def autowireApi(path: String): Action[RawBuffer] = Action.async(parse.raw) {
    implicit request =>
      log.debug(s"Request path: $path")

      // get the request body as ByteString
      val b = request.body.asBytes(parse.UNLIMITED).get

      // call Autowire route
      val router = Router.route[Api](ApiService(airportConfig, shiftsActor, fixedPointsActor, staffMovementsActor))

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
  def headLineFigures(forecastMinutes: Set[CrunchMinute], terminalName: TerminalName) = {
    val headlines = forecastMinutes
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

  def rollUpForWeek(forecastMinutes: Set[CrunchMinute], staffMinutes: Set[StaffMinute], terminalName: TerminalName): Map[MillisSinceEpoch, immutable.Seq[ForecastTimeSlot]] = {
    val actualStaffByMinute = staffByTimeSlot(15)(staffMinutes)
    groupByX(15)(terminalCrunchMinutesByMinute(forecastMinutes, terminalName), terminalName, Queues.queueOrder)
      .map {
        case (millis, cms) =>
          cms.foldLeft(
            ForecastTimeSlot(millis,actualStaffByMinute.getOrElse(millis, 0), 0))(
            (fts, cm) => fts
              .copy(required = fts.required + cm.deskRec)
          )
      }
      .groupBy(forecastTimeSlot => getLocalLastMidnight(SDate(forecastTimeSlot.startMillis)).millisSinceEpoch)
  }
}

case class GetTerminalCrunch(terminalName: TerminalName)
