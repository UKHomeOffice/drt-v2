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
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import play.api.http.HttpEntity
import services.Crunch.{CrunchMinute, midnightThisMorning}
import services.RunnableCrunchGraph

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

  def pcpArrivalTimeForFlight(airportConfig: AirportConfig)
                             (walkTimeProvider: FlightWalkTime)
                             (flight: Arrival): MilliDate = pcpFrom(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis, walkTimeProvider)(flight)

}

object SystemActors {
  type SplitsProvider = (Arrival) => Option[SplitRatios]
}


trait SystemActors {
  self: AirportConfProvider =>

  implicit val system: ActorSystem

  val config: Configuration

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val pcpArrivalTimeCalculator: (Arrival) => MilliDate = PaxFlow.pcpArrivalTimeForFlight(airportConfig)(flightWalkTimeProvider)

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

  val manifestsSource: Source[Set[VoyageManifest], NotUsed] = Source.fromGraph(new VoyageManifestsGraphStage(advPaxInfoProvider, voyageManifestsActor))

  RunnableCrunchGraph(
    flightsSource(mockProd, airportConfig.portCode),
    manifestsSource,
    crunchFlow,
    crunchStateActor
  ).run()(actorMaterializer)

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

  if (portCode == "LHR") config.getString("lhr.blackjack_url").map(csvUrl => {
    val threeMinutesInterval = 3 * 60 * 1000

    import SDate.implicits._

    Deskstats.startBlackjack(csvUrl, actualDesksActor, threeMinutesInterval milliseconds, previousDay(SDate.now()))
  })

  def previousDay(date: MilliDate): SDateLike = {
    val oneDayInMillis = 60 * 60 * 24 * 1000L
    SDate(date.millisSinceEpoch - oneDayInMillis)
  }

  def createApiService = new ApiService(airportConfig) with GetFlightsFromActor with CrunchFromCrunchState {

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
  }

  trait CrunchFromCrunchState {
    val crunchStateActor: AskableActorRef = ctrl.crunchStateActor

    def getTerminalCrunchResult(terminalName: TerminalName, pointInTime: Long): Future[TerminalCrunchResult] = {
      val actor: AskableActorRef = if (pointInTime > 0) {
        val crunchStateReadActorProps = Props(classOf[CrunchStateReadActor], SDate(pointInTime), airportConfig.queues)
        system.actorOf(crunchStateReadActorProps, "crunchStateReadActor" + UUID.randomUUID().toString)
      } else crunchStateActor

      val terminalCrunchResult = actor ? GetTerminalCrunch(terminalName)

      terminalCrunchResult.map {
        case tcr@ TerminalCrunchResult(_) => tcr
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
        val flights = askable.ask(GetFlights)(Timeout(100 milliseconds))
        flights.recover {
          case _ => FlightsNotReady()
        }.map {
          case FlightsNotReady() => Left(FlightsNotReady())
          case FlightsWithSplits(fs) => Right(FlightsWithSplits(fs))
        }
      }
    }

    override def getFlightsWithSplitsAtDate(pointInTime: MillisSinceEpoch): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
      val crunchStateReadActorProps = Props(classOf[CrunchStateReadActor], SDate(pointInTime), airportConfig.queues)
      val crunchStateReadActor: AskableActorRef = system.actorOf(crunchStateReadActorProps, "crunchStateReadActor" + UUID.randomUUID().toString)

      log.info(s"asking $crunchStateReadActor for flightsWithSplits")
      val flights = crunchStateReadActor.ask(GetFlights)(Timeout(5 seconds))

      flights.recover {
        case e: Throwable =>
          log.info(s"GetFlights failed: $e")
          Left(FlightsNotReady())
      }.map {
        case fs: FlightsWithSplits =>
          log.info(s"GetFlights success: ${fs.flights.length} flights")
          Right(fs)
        case e =>
          log.info(s"GetFlights failed: $e")
          Left(FlightsNotReady())
      }
    }
  }

  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }

  def getDesksAndQueuesCSV(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    implicit val timeout: Timeout = Timeout(5 seconds)

    val actor: AskableActorRef = system.actorOf(
      Props(classOf[CrunchStateReadActor], SDate(pointInTime.toLong), airportConfig.queues),
      "crunchStateReadActor" + UUID.randomUUID().toString
    )

    val pitMilliDate = MilliDate(pointInTime.toLong)
    val portCrunchResult = actor ? GetCrunchMinutes
    val fileName = s"$terminalName-desks-and-queues-${pitMilliDate.getFullYear()}-${pitMilliDate.getMonth()}-${pitMilliDate.getDate()}T${pitMilliDate.getHours()}-${pitMilliDate.getMinutes()}"

    portCrunchResult.map {
      case Some(cm: Set[CrunchMinute]) =>

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

  val cacheActorRef: AskableActorRef = system.actorOf(Props(classOf[CachingCrunchReadActor]), name = "cache-actor")

  def flightsAtTimestamp(millis: MillisSinceEpoch) = {
    implicit val timeout: Timeout = Timeout(5 seconds)
    val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], SDate(millis), airportConfig.queues), GetFlights)
    val flights = cacheActorRef ? query
    flights
  }

  def getFlightsWithSplitsCSV(pointInTime: String, terminalName: TerminalName) = Action.async {

    implicit val timeout: Timeout = Timeout(10 seconds)

//    val actor: AskableActorRef = system.actorOf(
//      Props(classOf[CrunchStateReadActor], SDate(pointInTime.toLong), airportConfig.queues),
//      "crunchStateReadActor" + UUID.randomUUID().toString
//    )

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
