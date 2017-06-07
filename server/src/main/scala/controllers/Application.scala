package controllers

import java.nio.ByteBuffer

import actors.{CrunchActor, FlightsActor, GetFlights, GetFlightsWithSplits}
import akka.Done
import akka.actor._
import akka.pattern.{AskableActorRef, _}
import akka.stream.Materializer
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import controllers.SystemActors.SplitsProvider
import drt.chroma.chromafetcher.ChromaFetcher
import drt.http.ProdSendAndReceive
import drt.server.feeds.chroma.{ChromaFlightFeed, MockChroma, ProdChroma}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{AirportConfig, Api, ApiFlight, CrunchResult, _}
import org.joda.time.chrono.ISOChronology
import org.joda.time.{DateTime, DateTimeZone}
import passengersplits.core.PassengerSplitsInfoByPortRouter
import passengersplits.s3.SimpleAtmosReader
import play.api.mvc._
import play.api.{Configuration, Environment}
import services.PcpArrival._
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

  override def read[R: Pickler](p: ByteBuffer) = Unpickle[R].fromBytes(p)

  def myroute[Trait](target: Trait): Router = macro MyMacros.routeMacro[Trait, ByteBuffer]

  override def write[R: Pickler](r: R) = Pickle.intoBytes(r)
}

trait Core {
  def system: ActorSystem
}


object PaxFlow {
  def makeFlightPaxFlowCalculator(
                                   splitRatioForFlight: (ApiFlight) => Option[SplitRatios],
                                   pcpArrivalTimeForFlight: (ApiFlight) => MilliDate
                                 ): (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val provider = PaxLoadCalculator.flightPaxFlowProvider(splitRatioForFlight, pcpArrivalTimeForFlight)
    (flight) => provider(flight)
  }

  def splitRatioForFlight(splitsProviders: List[SplitProvider])(flight: ApiFlight): Option[SplitRatios] = SplitsProvider.splitsForFlight(splitsProviders)(flight)

  def pcpArrivalTimeForFlight(airportConfig: AirportConfig)
                             (walkTimeProvider: FlightWalkTime)
                             (flight: ApiFlight): MilliDate = pcpFrom(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis, walkTimeProvider)(flight)

}

class ProdCrunchActor(hours: Int,
                      val airportConfig: AirportConfig,
                      val _flightPaxTypeAndQueueCountsFlow: (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)],
                      timeProvider: () => DateTime
                     ) extends CrunchActor(hours, airportConfig, timeProvider) {
  override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = {
    log.info(s"Looking for defaultProcessingTime($terminalName)($paxTypeAndQueue) in ${airportConfig.defaultProcessingTimes}")
    airportConfig.defaultProcessingTimes(terminalName)(paxTypeAndQueue)
  }

  override def flightPaxTypeAndQueueCountsFlow(flight: ApiFlight): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    log.info(s"wtfwtf: ${flight}, ${_flightPaxTypeAndQueueCountsFlow}")
    _flightPaxTypeAndQueueCountsFlow(flight)
  }
}

object SystemActors {
  type SplitsProvider = (ApiFlight) => Option[SplitRatios]
}


trait SystemActors extends Core {
  self: AirportConfProvider =>

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val paxFlowCalculator: (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = PaxFlow.makeFlightPaxFlowCalculator(
    PaxFlow.splitRatioForFlight(splitsProviders),
    PaxFlow.pcpArrivalTimeForFlight(airportConfig)(flightWalkTimeProvider))

  val crunchActor: ActorRef = system.actorOf(Props(classOf[ProdCrunchActor], 24,
    airportConfig,
    paxFlowCalculator,
    () => DateTime.now()), "crunchActor")

  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")
  val flightsActor: ActorRef = system.actorOf(Props(classOf[FlightsActor], crunchActor, flightPassengerSplitReporter, csvSplitsProvider), "flightsActor")
  val crunchByAnotherName: ActorSelection = system.actorSelection("crunchActor")
  val flightsActorAskable: AskableActorRef = flightsActor

  def csvSplitsProvider: SplitsProvider
  def splitsProviders(): List[SplitsProvider]

  def flightWalkTimeProvider(flight: ApiFlight): Millis
}


trait AirportConfiguration {
  def airportConfig: AirportConfig
}


trait AirportConfProvider extends AirportConfiguration {
  val portCode = ConfigFactory.load().getString("portcode").toUpperCase

  def mockProd = sys.env.getOrElse("MOCK_PROD", "PROD").toUpperCase

  def getPortConfFromEnvVar(): AirportConfig = AirportConfigs.confByPort(portCode)

  def airportConfig: AirportConfig = getPortConfFromEnvVar()

}

trait ProdPassengerSplitProviders {
  self: AirportConfiguration with SystemActors =>
  private implicit val timeout = Timeout(5 seconds)

  import scala.concurrent.ExecutionContext.global

  override val csvSplitsProvider: (ApiFlight) => Option[SplitRatios] = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: ApiFlight): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: ApiFlight): Option[FastTrackPercentages] = Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight), 0d, 0d))

  def apiSplitsProv(flight: ApiFlight): Option[SplitRatios] =
    AdvPaxSplitsProvider.splitRatioProviderWithCsvPercentages(
      airportConfig.portCode)(
      flightPassengerSplitReporter)(
      egatePercentageProvider,
      fastTrackPercentageProvider
    )(flight)(timeout, global)

  val apiSplitsProvider: (ApiFlight) => Option[SplitRatios] = (flight: ApiFlight) => apiSplitsProv(flight)
  override val splitsProviders = List(apiSplitsProvider, csvSplitsProvider, SplitsProvider.defaultProvider(airportConfig))
}

trait ImplicitTimeoutProvider {
  implicit val timeout = Timeout(5 seconds)
}

class Application @Inject()(
                             implicit val config: Configuration,
                             implicit val mat: Materializer,
                             env: Environment,
                             override val system: ActorSystem,
                             ec: ExecutionContext
                           )
  extends Controller with Core
    with AirportConfProvider
    with ProdPassengerSplitProviders
    with SystemActors with ImplicitTimeoutProvider {
  ctrl =>
  val log = system.log

  log.info(s"ISOChronology.getInstance: ${ISOChronology.getInstance}")
  private val systemTimeZone = System.getProperty("user.timezone")
  log.info(s"System.getProperty(user.timezone): ${systemTimeZone}")
  assert(systemTimeZone == "UTC")

  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))

  override def flightWalkTimeProvider(flight: ApiFlight): Millis = gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, airportConfig.defaultWalkTimeMillis)(flight)

  val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = ctrl.system
  }

  log.info(s"Application using airportConfig $airportConfig")

  def createApiService = new ApiService(airportConfig) with GetFlightsFromActor with CrunchFromCache {
    override def flightPaxTypeAndQueueCountsFlow(flight: ApiFlight): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = paxFlowCalculator(flight)

    override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = airportConfig.defaultProcessingTimes(terminalName)(paxTypeAndQueue)

    override implicit val timeout: Timeout = Timeout(5 seconds)

    def actorSystem: ActorSystem = system

    override def flightPassengerReporter: ActorRef = ctrl.flightPassengerSplitReporter

  }

  trait CrunchFromCache {
    self: CrunchResultProvider =>
    implicit val timeout: Timeout = Timeout(5 seconds)
    val crunchActor: AskableActorRef = ctrl.crunchActor

    def getLatestCrunchResult(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]] = {
      tryCrunch(terminalName, queueName)
    }
  }

  trait GetFlightsFromActor extends FlightsService {
    override def getFlights(start: Long, end: Long): Future[List[ApiFlight]] = {
      val flights: Future[Any] = ctrl.flightsActorAskable ? GetFlights
      val fsFuture = flights.collect {
        case Flights(fs) => fs
      }
      fsFuture
    }

    override def getFlightsWithSplits(start: Long, end: Long): Future[FlightsWithSplits] = {
      val askable = ctrl.flightsActorAskable
      log.info(s"asking $askable for flightsWithSplits")
      implicit val timout = 100 seconds
      val flights: Future[Any] = askable.ask(GetFlightsWithSplits)(timout)
      val fsFuture = flights.collect {
        case flightsWithSplits: FlightsWithSplits => flightsWithSplits
      }
      fsFuture
    }
  }

  def flightsSource(prodMock: String, portCode: String): Source[Flights, Cancellable] = {
    portCode match {
      case "LHR" =>
        LHRFlightFeed().map(Flights(_))
      case "EDI" =>
        createChromaFlightFeed(prodMock).chromaEdiFlights().map(Flights(_))
      case _ =>
        createChromaFlightFeed(prodMock).chromaVanillaFlights().map(Flights(_))
    }
  }

  private def createChromaFlightFeed(prodMock: String) = {
    val fetcher = prodMock match {
      case "MOCK" => MockChroma(system)
      case "PROD" => ProdChroma(system)
    }
    ChromaFlightFeed(log, fetcher)

  }

  val copiedToApiFlights = flightsSource(mockProd, portCode)
  copiedToApiFlights.runWith(Sink.actorRef(flightsActor, OnComplete))

  import passengersplits.polling.{AtmosManifestFilePolling => afp}

  /// PassengerSplits reader
  import SDate.implicits._

  val bucket = config.getString("atmos.s3.bucket").getOrElse(throw new Exception("You must set ATMOS_S3_BUCKET for us to poll for AdvPaxInfo"))
  val atmosHost = config.getString("atmos.s3.url").getOrElse(throw new Exception("You must set ATMOS_S3_URL"))
  val pollingDone: Future[Done] = afp.beginPolling(
    SimpleAtmosReader(bucket, atmosHost, log),
    ctrl.flightPassengerSplitReporter,
    afp.previousDayDqFilename(SDate.now()),
    portCode,
    afp.tickingSource(1 seconds, 1 minutes),
    batchAtMost = 400 seconds)

  pollingDone.onComplete(
    pollingCompletion =>
      log.warning(s"Atmos Polling completed with ${pollingCompletion}")
  )
  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }

  def autowireApi(path: String) = Action.async(parse.raw) {
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

  def logging = Action(parse.anyContent) {
    implicit request =>
      request.body.asJson.foreach {
        msg =>
          log.info(s"CLIENT - $msg")
      }
      Ok("")
  }
}





