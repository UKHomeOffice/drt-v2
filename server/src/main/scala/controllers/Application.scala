package controllers

import java.net.URL
import java.nio.ByteBuffer

import actors.{CrunchActor, FlightsActor, GetFlights}
import akka.actor._
import akka.event._
import akka.pattern._
import akka.pattern.AskableActorRef
import akka.stream.Materializer
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
import http.ProdSendAndReceive
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import passengersplits.core.PassengerSplitsInfoByPortRouter
import passengersplits.core.ZipUtils.UnzippedFileContent
import passengersplits.s3._
import play.api.mvc._
import play.api.{Configuration, Environment}
import services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared.SplitRatiosNs.SplitRatios
import spatutorial.shared.{Api, ApiFlight, CrunchResult, FlightsApi, _}
import views.html.defaultpages.notFound

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import ExecutionContext.Implicits.global
//import scala.collection.immutable.Seq
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

  val flightsActor: ActorRef = system.actorOf(Props(classOf[FlightsActor], crunchActor), "flightsActor")
  val crunchByAnotherName: ActorSelection = system.actorSelection("crunchActor")
  val flightsActorAskable: AskableActorRef = flightsActor
  val flightPassengerSplitReporter = system.actorOf(Props[PassengerSplitsInfoByPortRouter], name = "flight-pax-reporter")

  def splitProviders(): List[SplitsProvider]

}

trait ChromaFetcherLike {
  def system: ActorSystem

  def chromafetcher: ChromaFetcher
}

case class MockChroma(system: ActorSystem) extends ChromaFetcherLike {
  self =>
  system.log.info("Mock Chroma init")
  override val chromafetcher = new ChromaFetcher with MockedChromaSendReceive {
    implicit val system: ActorSystem = self.system
  }
}

case class ProdChroma(system: ActorSystem) extends ChromaFetcherLike {
  self =>
  override val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = self.system
  }
}

case class ChromaFlightFeed(log: LoggingAdapter, chromaFetcher: ChromaFetcherLike) extends {
  flightFeed =>

  object EdiChroma {
    val ArrivalsHall1 = "A1"
    val ArrivalsHall2 = "A2"
    val ediMapTerminals = Map(
      "T1" -> ArrivalsHall1,
      "T2" -> ArrivalsHall2
    )

    val ediMapping = chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()).map(csfs =>
      csfs.map(ediBaggageTerminalHack(_)).map(csf => ediMapTerminals.get(csf.Terminal) match {
        case Some(renamedTerminal) =>
          csf.copy(Terminal = renamedTerminal)
        case None => csf
      })
    )

    def ediBaggageTerminalHack(csf: ChromaSingleFlight) = {
      if (csf.BaggageReclaimId == "7") csf.copy(Terminal = ArrivalsHall2) else csf
    }
  }

  val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 100 seconds)

  def apiFlightCopy(ediMapping: Source[Seq[ChromaSingleFlight], Cancellable]) = {
    ediMapping.map(flights =>
      flights.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        ApiFlight(
          Operator = flight.Operator,
          Status = flight.Status, EstDT = flight.EstDT,
          ActDT = flight.ActDT, EstChoxDT = flight.EstChoxDT,
          ActChoxDT = flight.ActChoxDT,
          Gate = flight.Gate,
          Stand = flight.Stand,
          MaxPax = flight.MaxPax,
          ActPax = flight.ActPax,
          TranPax = flight.TranPax,
          RunwayID = flight.RunwayID,
          BaggageReclaimId = flight.BaggageReclaimId,
          FlightID = flight.FlightID,
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          ICAO = flight.ICAO,
          IATA = flight.IATA,
          Origin = flight.Origin,
          SchDT = flight.SchDT,
          PcpTime = pcpTime
        )
      }).toList)
  }

  val copiedToApiFlights = apiFlightCopy(EdiChroma.ediMapping).map(Flights(_))

  def chromaEdiFlights(): Source[List[ApiFlight], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 10 seconds)

    def ediMapping = chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()).map(csfs =>
      csfs.map(EdiChroma.ediBaggageTerminalHack(_)).map(csf => EdiChroma.ediMapTerminals.get(csf.Terminal) match {
        case Some(renamedTerminal) =>
          csf.copy(Terminal = renamedTerminal)
        case None => csf
      })
    )

    apiFlightCopy(ediMapping)
  }

  def chromaVanillaFlights(): Source[List[ApiFlight], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 10 seconds)
    apiFlightCopy(chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()))
  }
}

case class LHRLiveFlight(
                          term: String,
                          flightCode: String,
                          operator: String,
                          from: String,
                          airportName: String,
                          scheduled: org.joda.time.DateTime,
                          estimated: Option[String],
                          touchdown: Option[String],
                          estChox: Option[String],
                          actChox: Option[String],
                          stand: Option[String],
                          maxPax: Option[Int],
                          actPax: Option[Int],
                          connPax: Option[Int]
                        ) {
  def flightNo = 23
}

case class LHRCsvException(originalLine: String, idx: Int, innerException: Throwable) extends Exception {
  override def toString = s"$originalLine : $idx $innerException"
}

object LHRFlightFeed {
  def parseDateTime(dateString: String) = pattern.parseDateTime(dateString)

  val pattern: DateTimeFormatter = DateTimeFormat.forPattern("HH:mm dd/MM/YYYY")
}

case class LHRFlightFeed() {
  val csvFile = "/LHR_DMNDDET_20161022_0547.csv"

  def opt(s: String) = if (s.isEmpty) None else Option(s)

  def pd(s: String) = LHRFlightFeed.parseDateTime(s)

  def optDate(s: String) = if (s.isEmpty) None else Option(s)

  def optInt(s: String) = if (s.isEmpty) None else Option(s.toInt)

  lazy val lhrFlights: Iterator[Try[LHRLiveFlight]] = {
    val resource: URL = getClass.getResource(csvFile)
    val bufferedSource = scala.io.Source.fromURL(resource)
    bufferedSource.getLines().zipWithIndex.drop(1).map { case (l, idx) =>

      val t = Try {
        val splitRow: Array[String] = l.split(",", -1)
        println(s"length ${splitRow.length} $l")
        val sq: (String) => String = (x) => x
        LHRLiveFlight(sq(splitRow(0)), sq(splitRow(1)), sq(splitRow(2)), sq(splitRow(3)),
          sq(splitRow(4)), pd(splitRow(5)),
          opt(splitRow(6)), opt(splitRow(7)), opt(splitRow(8)), opt(sq(splitRow(9))),
          opt(splitRow(10)),
          optInt(splitRow(11)),
          optInt(splitRow(12)),
          optInt(splitRow(13)))
      }
      t match {
        case Success(s) => Success(s)
        case Failure(t) => Failure(LHRCsvException(l, idx, t))
      }
    }
  }
  val walkTimeMinutes = 4

  lazy val successfulFlights = lhrFlights.collect { case Success(s) => s }

  lazy val copiedToApiFlights = Source(
    List(
      List[ApiFlight](),
      successfulFlights.map(flight => {
        val pcpTime: Long = flight.scheduled.plusMinutes(walkTimeMinutes).getMillis
        val schDtIso = flight.scheduled.toDateTimeISO().toString()
        val defaultPaxPerFlight = 200
        ApiFlight(
          Operator = flight.operator,
          Status = "UNK",
          EstDT = flight.estimated.getOrElse(""),
          ActDT = flight.touchdown.getOrElse(""),
          EstChoxDT = flight.estChox.getOrElse(""),
          ActChoxDT = flight.actChox.getOrElse(""),
          Gate = "",
          Stand = flight.stand.getOrElse(""),
          MaxPax = flight.maxPax.getOrElse(-1),
          ActPax = flight.actPax.getOrElse(defaultPaxPerFlight),
          TranPax = flight.connPax.getOrElse(-1),
          RunwayID = "",
          BaggageReclaimId = "",
          FlightID = flight.hashCode(),
          AirportID = "LHR",
          Terminal = flight.term,
          ICAO = flight.flightCode,
          IATA = flight.flightCode,
          Origin = flight.airportName,
          SchDT = schDtIso,
          PcpTime = pcpTime)
      }).toList)).map(x => FlightsApi.Flights(x))
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
  val splitProviders = List(SplitsProvider.csvProvider, SplitsProvider.defaultProvider(airportConfig))
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
    with SystemActors {
  ctrl =>
  val log = system.log

  implicit val timeout = Timeout(5 seconds)


  val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = ctrl.system
  }

  log.info(s"Application using airportConfig $airportConfig")

  def createApiService = new ApiService(airportConfig) with GetFlightsFromActor with CrunchFromCache {

    override implicit val timeout: Timeout = Timeout(5 seconds)

    def actorSystem: ActorSystem = system

    //    override def flightPassengerReporter: ActorRef = ctrl.flightPassengerSplitReporter

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
  }

  def flightsSource(prodMock: String, portCode: String): Source[Flights, Cancellable] = {
    val fetcher = prodMock match {
      case "MOCK" => MockChroma(system)
      case "PROD" => ProdChroma(system)
    }
    val chromaFlightFeed = ChromaFlightFeed(log, fetcher)

    portCode match {
      case "EDI" =>
        chromaFlightFeed.chromaEdiFlights().map(Flights(_))
      case _ =>
        chromaFlightFeed.chromaVanillaFlights().map(Flights(_))
    }
  }

  val copiedToApiFlights = flightsSource(mockProd, portCode)
  copiedToApiFlights.runWith(Sink.actorRef(flightsActor, OnComplete))

  /// PassengerSplits reader


  //  val lhrfeed = LHRFlightFeed()
  //  lhrfeed.copiedToApiFlights.runWith(Sink.actorRef(flightsActor, OnComplete))

  //  val copiedToApiFlights = apiFlightCopy(ediMapping).map(Flights(_))
  //  copiedToApiFlights.runWith(Sink.actorRef(flightsActor, OnComplete))

  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }

  def autowireApi(path: String) = Action.async(parse.raw) {
    implicit request =>
      println(s"Request path: $path")

      // get the request body as ByteString
      val b = request.body.asBytes(parse.UNLIMITED).get

      // call Autowire route
      val router = Router.myroute[Api](createApiService)
      //      router.§
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
      request.body.asJson.foreach { msg =>
        println(s"CLIENT - $msg")
      }
      Ok("")
  }
}


object FilePolling {
  def beginPolling(log: LoggingAdapter, flightPassengerReporter: ActorRef)(implicit actorSystem: ActorSystem, mat: Materializer) = {
    val statefulPoller = StatefulLocalFileSystemPoller(Some("drt_dq_1701"))

    val promiseDone = PromiseSignals.promisedDone

    val subscriberFlightActor = Sink.actorSubscriber(WorkerPool.props(flightPassengerReporter))

    val runOnce = FileSystemAkkaStreamReading.runOnce(log)(statefulPoller.unzippedFileProvider) _
    val unzipFlow = Flow[String].mapAsync(128)(statefulPoller.unzippedFileProvider.zipFilenameToEventualFileContent(_))
      .mapConcat(unzippedFileContents => unzippedFileContents.map(uzfc => VoyagePassengerInfoParser.parseVoyagePassengerInfo(uzfc.content)))
      .collect {
        case Success(vpi) if vpi.ArrivalPortCode == "STN" => vpi
      }.map(uzfc => {
      log.info(s"Processing $uzfc")
      uzfc
    })

    val unzippedSink = unzipFlow.to(subscriberFlightActor)
    val i = 1
    runOnce(i, (td) => promiseDone.complete(td), statefulPoller.onNewFileSeen, unzippedSink)
    val resultOne = Await.result(promiseDone.future, 10 seconds)
  }
}


