package controllers

import java.nio.ByteBuffer
import java.util.UUID

import actors._
import actors.pointInTime.{CrunchStateReadActor, GetCrunchMinutes}
import akka.NotUsed
import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern.{AskableActorRef, _}
import akka.stream._
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import play.api.http.HttpEntity
import services.graphstages.Crunch.{CrunchMinute, CrunchState, midnightThisMorning}
import services.SDate
import services.graphstages.{CrunchGraphStage, RunnableCrunchGraph, StaffingStage, VoyageManifestsGraphStage}

import scala.collection.immutable.Map
//import controllers.Deskstats.log
import controllers.SystemActors.SplitsProvider
import drt.server.feeds.chroma.{ChromaFlightFeed, MockChroma, ProdChroma}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{AirportConfig, Api, Arrival, CrunchResult, _}
import org.joda.time.chrono.ISOChronology
import play.api.mvc._
import play.api.{Configuration, Environment}
import services.PcpArrival._
import services.SDate.implicits._
import services.SplitsProvider.SplitProvider
import services._
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount}

import scala.collection.immutable.IndexedSeq
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

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val pcpArrivalTimeCalculator: (Arrival) => MilliDate = PaxFlow.pcpArrivalTimeForFlight(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis)(flightWalkTimeProvider)

  val actorMaterializer = ActorMaterializer()

  val crunchStateActor: ActorRef = system.actorOf(Props(classOf[CrunchStateActor], airportConfig.queues), name = "crunch-state-actor")
  val askableCrunchStateActor: AskableActorRef = crunchStateActor
  val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor]), name = "voyage-manifests-actor")

  val initialFlightsFuture: Future[List[ApiFlightWithSplits]] = askableCrunchStateActor.ask(GetFlights)(new Timeout(10 seconds)).map {
    case FlightsWithSplits(flights) => flights
    case FlightsNotReady => List()
  }
  val crunchFlow = new CrunchGraphStage(
    initialFlightsFuture,
    airportConfig.slaByQueue,
    airportConfig.minMaxDesksByTerminalQueue,
    airportConfig.defaultProcessingTimes.head._2,
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
    airportConfig.terminalNames.toSet,
    airportConfig.defaultPaxSplits,
    historicalSplitsProvider,
    pcpArrivalTimeCalculator,
    midnightThisMorning,
    1440
  )

  val chroma = ChromaFlightFeed(system.log, ProdChroma(system))

  val bucket: String = config.getString("atmos.s3.bucket").getOrElse(throw new Exception("You must set ATMOS_S3_BUCKET for us to poll for AdvPaxInfo"))
  val atmosHost: String = config.getString("atmos.s3.url").getOrElse(throw new Exception("You must set ATMOS_S3_URL"))
  val advPaxInfoProvider = VoyageManifestsProvider(atmosHost, bucket, airportConfig.portCode)

  val manifestsSource: Source[VoyageManifests, NotUsed] = Source.fromGraph(new VoyageManifestsGraphStage(advPaxInfoProvider, voyageManifestsActor))
  val shiftsSource = Source.actorRef(1, OverflowStrategy.dropHead)
  val fixedPointsSource = Source.actorRef(1, OverflowStrategy.dropHead)
  val staffMovementsSource = Source.actorRef(1, OverflowStrategy.dropHead)
  def initialCrunchStateFuture: Future[Option[CrunchState]] = askableCrunchStateActor.ask(GetState)(new Timeout(10 seconds)).map {
    case None => None
    case Some(cs: CrunchState) => Option(cs)
  }

  val staffingGraphStage = new StaffingStage(initialCrunchStateFuture, airportConfig.minMaxDesksByTerminalQueue, airportConfig.slaByQueue)

  val (_, _, shiftsInput, fixedPointsInput, staffMovementsInput, _, _, _) = RunnableCrunchGraph(
    flightsSource(mockProd, airportConfig.portCode),
    manifestsSource,
    shiftsSource,
    fixedPointsSource,
    staffMovementsSource,
    staffingGraphStage,
    crunchFlow,
    crunchStateActor
  ).run()(actorMaterializer)

  val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor], shiftsInput))
  val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor], fixedPointsInput))
  val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor], staffMovementsInput))

  val actualDesksActor: ActorRef = system.actorOf(Props[DeskstatsActor])

  def historicalSplitsProvider: SplitsProvider = SplitsProvider.csvProvider

  def flightWalkTimeProvider(flight: Arrival): Millis

  def flightsSource(prodMock: String, portCode: String): Source[Flights, Cancellable] = {
    portCode match {
      case "LHR" =>
        LHRFlightFeed().map(Flights)
      case "EDI" =>
        createChromaFlightFeed(prodMock).chromaEdiFlights().map(Flights)
      case _ =>
        createChromaFlightFeed(prodMock).chromaVanillaFlights().map(Flights)
    }
  }

  def createChromaFlightFeed(prodMock: String): ChromaFlightFeed = {
    val fetcher = prodMock match {
      case "MOCK" => MockChroma(system)
      case "PROD" => ProdChroma(system)
    }
    ChromaFlightFeed(system.log, fetcher)
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

  import scala.concurrent.ExecutionContext.global

  val csvSplitsProvider: (Arrival) => Option[SplitRatios] = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: Arrival): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: Arrival): Option[FastTrackPercentages] =
    Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight), 0d, 0d))

  private implicit val timeout = Timeout(250 milliseconds)
}

trait ProdWalkTimesProvider {
  self: AirportConfProvider =>
  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))

  def flightWalkTimeProvider(flight: Arrival): Millis = gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, airportConfig.defaultWalkTimeMillis)(flight)
}

trait ImplicitTimeoutProvider {
  implicit val timeout = Timeout(1 second)
}

class Application @Inject()(
                             implicit val config: Configuration,
                             implicit val mat: Materializer,
                             env: Environment,
                             override val system: ActorSystem,
                             ec: ExecutionContext
                           )
  extends Controller
    with AirportConfProvider
    with ProdPassengerSplitProviders
    with SystemActors with ImplicitTimeoutProvider
    with ProdWalkTimesProvider {
  ctrl =>
  val log: LoggingAdapter = system.log

  log.info(s"ISOChronology.getInstance: ${ISOChronology.getInstance}")
  private val systemTimeZone = System.getProperty("user.timezone")
  log.info(s"System.getProperty(user.timezone): $systemTimeZone")
  assert(systemTimeZone == "UTC")

  log.info(s"Application using airportConfig $airportConfig")

  val cacheActorRef: AskableActorRef = system.actorOf(Props(classOf[CachingCrunchReadActor]), name = "cache-actor")

  if (portCode == "LHR") config.getString("lhr.blackjack_url").map(csvUrl => {
    val threeMinutesInterval = 3 * 60 * 1000

    import SDate.implicits._

    Deskstats.startBlackjack(csvUrl, actualDesksActor, threeMinutesInterval milliseconds, previousDay(SDate.now()))
  })

  def previousDay(date: MilliDate): SDateLike = {
    val oneDayInMillis = 60 * 60 * 24 * 1000L
    SDate(date.millisSinceEpoch - oneDayInMillis)
  }

  val createApiService = new ApiService(airportConfig, shiftsActor, fixedPointsActor, staffMovementsActor) with GetFlightsFromActor with CrunchFromCrunchState {

    override implicit val timeout: Timeout = Timeout(5 seconds)

    def actorSystem: ActorSystem = system

    override def getActualDeskStats(pointInTime: Long): Future[ActualDeskStats] = {
      val actor: AskableActorRef = if (pointInTime > 0) {
        val DeskstatsReadActorProps = Props(classOf[DeskstatsReadActor], SDate(pointInTime))
        actorSystem.actorOf(DeskstatsReadActorProps, "crunchStateReadActor" + UUID.randomUUID().toString)
      } else actualDesksActor

      val futureDesks = actor ? GetActualDeskStats()
      futureDesks.map(_.asInstanceOf[ActualDeskStats])
    }

    override def askableCacheActorRef: AskableActorRef = cacheActorRef

    override def crunchStateActor: AskableActorRef = ctrl.crunchStateActor
  }

  trait CrunchFromCrunchState {

    def getTerminalCrunchResult(terminalName: TerminalName, pointInTime: Long): Future[TerminalCrunchResult] = {
      implicit val timeout = Timeout(60 seconds)
      if (pointInTime > 0) {
        val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], SDate(pointInTime.toLong), airportConfig.queues), GetTerminalCrunch(terminalName))
        val terminalCrunchResult = cacheActorRef ? query

        terminalCrunchResult.map {
          case tcr@ TerminalCrunchResult(_) => tcr
        }
      } else {
        val crunchStateActor: AskableActorRef = ctrl.crunchStateActor
        val terminalCrunchResult = crunchStateActor ? GetTerminalCrunch(terminalName)
        terminalCrunchResult.map {
          case tcr@ TerminalCrunchResult(_) => tcr
        }
      }
    }
  }

  trait GetFlightsFromActor extends FlightsService {

    override def getFlightsWithSplits(start: Long, end: Long): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
      if (start > 0) {
        getFlightsWithSplitsAtDate(start)
      } else {
        val askable = ctrl.crunchStateActor
        log.info(s"asking $askable for flightsWithSplits")
        val flights = askable.ask(GetFlights)(Timeout(1 second))
        flights.recover {
          case _ => FlightsNotReady()
        }.map {
          case FlightsNotReady() => Left(FlightsNotReady())
          case FlightsWithSplits(fs) => Right(FlightsWithSplits(fs))
        }
      }
    }

    override def getFlightsWithSplitsAtDate(pointInTime: MillisSinceEpoch): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
      implicit val timeout: Timeout = Timeout(60 seconds)
      val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], SDate(pointInTime), airportConfig.queues), GetFlights)
      val flights = cacheActorRef ? query

      flights.recover {
        case e: Throwable =>
          log.info(s"GetFlights failed: $e")
          Left(FlightsNotReady())
      }.map {
        case fs: FlightsWithSplits =>
          log.info(s"GetFlights success: ${fs.flights.length} flights")
          Right(fs)
        case e =>
          log.info(s"GetFlights Flights not ready: $e")
          Left(FlightsNotReady())
      }
    }
  }

  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }

  def getDesksAndQueuesCSV(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    implicit val timeout: Timeout = Timeout(5 seconds)

    val portCrunchResult: Future[CrunchMinutes] = crunchMinutesAtPointInTime(pointInTime)

    val pitMilliDate = MilliDate(pointInTime.toLong)

    val fileName = s"$terminalName-desks-and-queues-${pitMilliDate.getFullYear()}-${pitMilliDate.getMonth()}-${pitMilliDate.getDate()}T${pitMilliDate.getHours()}-${pitMilliDate.getMinutes()}"

    portCrunchResult.map {
      case CrunchMinutes(cm) =>
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

  def crunchMinutesAtPointInTime(pointInTime: String): Future[CrunchMinutes] = {
    val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], SDate(pointInTime.toLong), airportConfig.queues), GetCrunchMinutes)
    val portCrunchResult = cacheActorRef ? query
    portCrunchResult.map {
      case cms@ CrunchMinutes(_) => cms
    }
  }


  def flightsAtTimestamp(millis: MillisSinceEpoch) = {
    implicit val timeout: Timeout = Timeout(60 seconds)
    val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], SDate(millis), airportConfig.queues), GetFlights)
    val flights = cacheActorRef ? query
    flights
  }

  def getFlightsWithSplitsCSV(pointInTime: String, terminalName: TerminalName) = Action.async {

    implicit val timeout: Timeout = Timeout(60 seconds)

    val potMillidate = MilliDate(pointInTime.toLong)
    val flights = flightsAtTimestamp(pointInTime.toLong)
    val fileName = s"$terminalName-arrivals-${potMillidate.getFullYear()}-${potMillidate.getMonth()}-${potMillidate.getDate()}T${potMillidate.getHours()}-${potMillidate.getMinutes()}"

    flights.map {
      case FlightsWithSplits(fs) =>

        val csvData = CSVData.flightsWithSplitsToCSV(fs.filter(_.apiFlight.Terminal == terminalName))
        Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename='$fileName.csv'")),
          HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
        )
      case unexpected =>
        log.error(s"got the wrong thing: $unexpected")
        NotFound("")
    }
  }

  def autowireApi(path: String): Action[RawBuffer] = Action.async(parse.raw) {
    implicit request =>
      println(s"Request path: $path")

      // get the request body as ByteString
      val b = request.body.asBytes(parse.UNLIMITED).get

      // call Autowire route
      val router = Router.route[Api](createApiService)

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

case class GetTerminalCrunch(terminalName: TerminalName)

case class CrunchMinutes(crunchMinutes: Set[CrunchMinute])
