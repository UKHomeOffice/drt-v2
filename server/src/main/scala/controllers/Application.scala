package controllers

import java.nio.ByteBuffer
import java.nio.charset.Charset

import actors.{CrunchActor, FlightsActor, GetFlights, GetFlightsWithSplits}
import akka.NotUsed
import akka.actor._
import akka.event._
import akka.pattern._
import akka.pattern.AskableActorRef
import akka.stream.{Graph, Materializer, SinkShape}
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import autowire.Core.Router
import autowire.Macros
import autowire.Macros.MacroHelp
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import controllers.SystemActors.SplitsProvider
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.ChromaSingleFlight
import drt.chroma.{DiffingStage, StreamingChromaFlow}
import drt.server.feeds.chroma.{ChromaFlightFeed, MockChroma, ProdChroma}
import drt.http.ProdSendAndReceive
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import passengersplits.core.PassengerInfoRouterActor.{FlightPaxSplitBatchComplete, FlightPaxSplitBatchInit, PassengerSplitsAck}
import passengersplits.core.PassengerSplitsInfoByPortRouter
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.polling.{AtmosFilePolling, FilePolling}
import passengersplits.s3._
import play.api.mvc._
import play.api.{Configuration, Environment}
import services._
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{Api, ApiFlight, CrunchResult, FlightsApi, _}
import views.html.defaultpages.notFound
import drt.server.feeds.lhr.LHRFlightFeed
import services.SDate.JodaSDate

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import ExecutionContext.Implicits.global
import scala.Seq
//import scala.collection.immutable.Seq // do not import this here, it would break autowire.
import scala.reflect.macros.Context


object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {

  import scala.language.experimental.macros

  override def read[R: Pickler](p: ByteBuffer) = Unpickle[R].fromBytes(p)

  def myroute[Trait](target: Trait): Router = macro MyMacros.routeMacro[Trait, ByteBuffer]

  override def write[R: Pickler](r: R) = Pickle.intoBytes(r)
}

trait Core {
  def system: ActorSystem
}

class ProdCrunchActor(hours: Int, airportConfig: AirportConfig,
                      splitsProviders: List[SplitsProvider],
                      timeProvider: () => DateTime
                     ) extends CrunchActor(hours, airportConfig, timeProvider) {

  def splitRatioProvider = SplitsProvider.splitsForFlight(splitsProviders)

  def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue) = airportConfig.defaultProcessingTimes(terminalName)(paxTypeAndQueue)
}

object SystemActors {
  type SplitsProvider = (ApiFlight) => Option[SplitRatios]
}


trait SystemActors extends Core {
  self: AirportConfProvider =>

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val crunchActor: ActorRef = system.actorOf(Props(classOf[ProdCrunchActor], 24,
    airportConfig,
    splitProviders,
    () => DateTime.now()), "crunchActor")

  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")
  val flightsActor: ActorRef = system.actorOf(Props(classOf[FlightsActor], crunchActor, flightPassengerSplitReporter), "flightsActor")
  val crunchByAnotherName: ActorSelection = system.actorSelection("crunchActor")
  val flightsActorAskable: AskableActorRef = flightsActor

  def splitProviders(): List[SplitsProvider]
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

  val apiSplitsProvider = (flight: ApiFlight) => AdvPaxSplitsProvider.splitRatioProvider(airportConfig.portCode)(flightPassengerSplitReporter)(flight)(timeout, global)
  val splitProviders = List(apiSplitsProvider, SplitsProvider.csvProvider, SplitsProvider.defaultProvider(airportConfig))
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


  val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = ctrl.system
  }

  log.info(s"Application using airportConfig $airportConfig")

  def createApiService = new ApiService(airportConfig) with GetFlightsFromActor with CrunchFromCache {

    override implicit val timeout: Timeout = Timeout(5 seconds)

    def actorSystem: ActorSystem = system

    override def flightPassengerReporter: ActorRef = ctrl.flightPassengerSplitReporter

    override def splitRatioProvider = SplitsProvider.splitsForFlight(splitProviders)

    override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue) = airportConfig.defaultProcessingTimes(terminalName)(paxTypeAndQueue)
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

  import passengersplits.polling.{AtmosFilePolling => afp}

  /// PassengerSplits reader
  import SDate.implicits._

  afp.beginPolling(log,
    ctrl.flightPassengerSplitReporter,
    afp.previousDayDqFilename(SDate.now()),
    config.getString("atmos.s3.url").getOrElse(throw new Exception("You must set ATMOS_S3_URL")),
    config.getString("atmos.s3.bucket").getOrElse(throw new Exception("You must set ATMOS_S3_BUCKET for us to poll for AdvPaxInfo")),
    portCode
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





