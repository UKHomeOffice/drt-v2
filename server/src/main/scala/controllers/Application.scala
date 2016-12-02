package controllers

import java.net.URL
import java.nio.ByteBuffer

import akka.actor._
import akka.event._
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.ChromaSingleFlight
import drt.chroma.{DiffingStage, StreamingChromaFlow}
import http.ProdSendAndReceive
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import play.api.mvc._
import play.api.{Configuration, Environment}
import services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared.{Api, ApiFlight, CrunchResult, FlightsApi, _}

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {
  override def read[R: Pickler](p: ByteBuffer) = Unpickle[R].fromBytes(p)

  override def write[R: Pickler](r: R) = Pickle.intoBytes(r)
}

trait Core {
  def system: ActorSystem
}

object SplitsProvider {
  def splitsForFlight(providers: List[ApiFlight => Option[List[SplitRatio]]])(apiFlight: ApiFlight): Option[List[SplitRatio]] = {
    providers.foldLeft(None: Option[List[SplitRatio]])((prev, provider) => {
      prev match {
        case Some(split) => prev
        case None => provider(apiFlight)
      }
    })
  }
}

trait ProdSplitsProvider extends AirportConfProvider {
  def shouldUseCsvSplitsProvider: Boolean = {
    ConfigFactory.load.hasPath("passenger_splits_csv_url") && ConfigFactory.load.getString("passenger_splits_csv_url") != ""
  }

  def emptyProvider: (ApiFlight => Option[List[SplitRatio]]) = _ => Option.empty[List[SplitRatio]]

  def csvProvider: (ApiFlight) => Option[List[SplitRatio]] = {
    if (shouldUseCsvSplitsProvider)
      new CSVPassengerSplitsProvider {
        override def flightPassengerSplitLines = PassengerSplitsCSVReader.flightPaxSplitsLinesFromConfig
      }.splitRatioProvider
    else
      emptyProvider
  }

  def defaultProvider: (ApiFlight) => Some[List[SplitRatio]] = {
    _ => Some(getPortConfFromEnvVar.defaultPaxSplits)
  }

  def splitRatioProvider = SplitsProvider.splitsForFlight(List(csvProvider, defaultProvider)) _
}

class ProdCrunchActor(hours: Int, conf: AirportConfig) extends CrunchActor(hours, conf) with ProdSplitsProvider

trait SystemActors extends Core {
  self: AirportConfProvider =>


  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val crunchActor: ActorRef = system.actorOf(Props(classOf[ProdCrunchActor], 24, self.getPortConfFromEnvVar), "crunchActor")
  val flightsActor: ActorRef = system.actorOf(Props(classOf[FlightsActor], crunchActor), "flightsActor")
  val crunchByAnotherName: ActorSelection = system.actorSelection("crunchActor")
  val flightsActorAskable: AskableActorRef = flightsActor
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

  val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 10 seconds)

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

trait AirportConfProvider {
  def portCode = ConfigFactory.load().getString("portcode").toUpperCase

  def mockProd = sys.env.getOrElse("MOCK_PROD", "PROD").toUpperCase

  def getPortConfFromEnvVar: AirportConfig = {
    AirportConfigs.confByPort(portCode)
  }
}

class Application @Inject()(
                             implicit val config: Configuration,
                             implicit val mat: Materializer,
                             env: Environment,
                             override val system: ActorSystem,
                             ec: ExecutionContext
                           )
  extends Controller with Core with SystemActors with AirportConfProvider {
  ctrl =>
  val log = system.log

  implicit val timeout = Timeout(5 seconds)

  val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = ctrl.system
  }

  val apiService = createApiService

  def createApiService = new ApiService(getPortConfFromEnvVar) with GetFlightsFromActor with CrunchFromCache with ProdSplitsProvider

  trait CrunchFromCache {
    self: CrunchResultProvider =>
    implicit val timeout: Timeout = Timeout(5 seconds)
    val crunchActor: AskableActorRef = ctrl.crunchActor

    def getLatestCrunchResult(terminalName: TerminalName, queueName: QueueName): Future[CrunchResult] = {
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

  val fetcher = mockProd match {
    case "MOCK" => MockChroma(system)
    case "PROD" => ProdChroma(system)
  }

  val copiedToApiFlights: Source[Flights, Cancellable] = portCode match {
    case "EDI" =>
      ChromaFlightFeed(log, fetcher).chromaEdiFlights().map(Flights(_))
    case _ =>
      ChromaFlightFeed(log, fetcher).chromaVanillaFlights().map(Flights(_))
  }

  copiedToApiFlights.runWith(Sink.actorRef(flightsActor, OnComplete))

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
      Router.route[Api](apiService)(
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
