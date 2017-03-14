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
import scala.collection.JavaConversions._
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
import spatutorial.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import spatutorial.shared.SplitRatiosNs.SplitRatios
import spatutorial.shared.{Api, ApiFlight, CrunchResult, FlightsApi, _}
import views.html.defaultpages.notFound

import sys.process._
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

case class ChromaFlightFeed(log: LoggingAdapter, chromaFetcher: ChromaFetcherLike) {
  flightFeed =>
  val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 100 seconds)

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
                          estimated: Option[org.joda.time.DateTime],
                          touchdown: Option[org.joda.time.DateTime],
                          estChox: Option[org.joda.time.DateTime],
                          actChox: Option[org.joda.time.DateTime],
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
  val pattern: DateTimeFormatter = DateTimeFormat.forPattern("HH:mm dd/MM/YYYY")

  def parseDateTime(dateString: String) = pattern.parseDateTime(dateString)

  def apply(): Source[List[ApiFlight], Cancellable] = {
    val username = ConfigFactory.load.getString("lhr_live_username")
    val password = ConfigFactory.load.getString("lhr_live_password")

    println(s"preparing lhrfeed")

    val pollFrequency = 1 minute
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[Source[List[ApiFlight], NotUsed], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((t) => {
        println(s"about to request csv")
        val csvContents = Seq("/usr/local/bin/lhr-live-fetch-latest-feed.sh", "-u", username, "-p", password).!!
        println(s"Got csvContents: $csvContents")
        val f = LHRFlightFeed(csvContents.split("\n").toIterator).copiedToApiFlights
        println(s"copied to api flights")
        f
      })

    val recoverableTicking: Source[List[ApiFlight], Cancellable] = tickingSource.flatMapConcat(s => s.map(x => x))

    recoverableTicking
  }
}

case class LHRFlightFeed(csvLines: Iterator[String]) {


  def opt(s: String) = if (s.isEmpty) None else Option(s)

  def pd(s: String) = LHRFlightFeed.parseDateTime(s)

  def optDate(s: String) = if (s.isEmpty) None else Option(pd(s))

  def optInt(s: String) = if (s.isEmpty) None else Option(s.toInt)

  lazy val lhrFlights: Iterator[Try[LHRLiveFlight]] = {

    csvLines.zipWithIndex.drop(1).map { case (l, idx) =>

      val t: Try[LHRLiveFlight] = Try {
        val csv = CSVParser.parse(l, CSVFormat.DEFAULT)

        val csvRecords = csv.iterator().toList
        csvRecords match {
          case csvRecord :: Nil =>
            def splitRow(i: Int) =  csvRecord.get(i)
            val sq: (String) => String = (x) => x
            LHRLiveFlight(
              term = s"T${sq(splitRow(0))}",
              flightCode = sq(splitRow(1)),
              operator = sq(splitRow(2)),
              from = sq(splitRow(3)),
              airportName = sq(splitRow(4)),
              scheduled = pd(splitRow(5)),
              estimated = optDate(splitRow(6)),
              touchdown = optDate(splitRow(7)),
              estChox = optDate(splitRow(8)),
              actChox = optDate(sq(splitRow(9))),
              stand = opt(splitRow(10)),
              maxPax = optInt(splitRow(11)),
              actPax = optInt(splitRow(12)),
              connPax = optInt(splitRow(13)))
          case Nil => throw new Exception(s"Invalid CSV row: $l")
        }
      }
      t match {
        case Success(s) => Success(s)
        case Failure(t) =>
          Failure(LHRCsvException(l, idx, t))
      }
    }
  }
  val walkTimeMinutes = 4

  lazy val successfulFlights = lhrFlights.collect { case Success(s) => s }

  def dateOptToStringOrEmptyString = (dto: Option[DateTime]) => dto.map(_.toDateTimeISO.toString()).getOrElse("")

  lazy val copiedToApiFlights: Source[List[ApiFlight], NotUsed] = Source(
    List(
      successfulFlights.map(flight => {
        val pcpTime: Long = flight.scheduled.plusMinutes(walkTimeMinutes).getMillis
        val schDtIso = flight.scheduled.toDateTimeISO().toString()
        val defaultPaxPerFlight = 200
        ApiFlight(
          Operator = flight.operator,
          Status = "UNK",
          EstDT = dateOptToStringOrEmptyString(flight.estimated),
          ActDT = dateOptToStringOrEmptyString(flight.touchdown),
          EstChoxDT = dateOptToStringOrEmptyString(flight.estChox),
          ActChoxDT = dateOptToStringOrEmptyString(flight.actChox),
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
          Origin = flight.from,
          SchDT = schDtIso,
          PcpTime = pcpTime)
      }).toList))
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


  /// PassengerSplits reader
  AtmosFilePolling.beginPolling(log,
    ctrl.flightPassengerSplitReporter,
    Some("drt_dq_17030"),
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
      request.body.asJson.foreach { msg =>
        println(s"CLIENT - $msg")
      }
      Ok("")
  }
}





