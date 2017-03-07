package controllers

import java.nio.ByteBuffer

import actors.{CrunchActor, FlightsActor, GetFlights}
import akka.NotUsed
import akka.actor._
import akka.event._
import akka.pattern._
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
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
import play.api.mvc._
import play.api.{Configuration, Environment}
import services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared.{Api, ApiFlight, CrunchResult, _}

import sys.process._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import ExecutionContext.Implicits.global


object Router extends autowire.Server[ByteBuffer, Pickler, Pickler] {
  override def read[R: Pickler](p: ByteBuffer) = Unpickle[R].fromBytes(p)

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
  type SplitsProvider = (ApiFlight) => Option[List[SplitRatio]]
}


trait SystemActors extends Core {
  self: AirportConfProvider =>

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  def splitProviders(): List[SplitsProvider]

  val crunchActor: ActorRef = system.actorOf(Props(classOf[ProdCrunchActor], 24,
    airportConfig,
    splitProviders,
    () => DateTime.now()), "crunchActor")

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
  val pattern: DateTimeFormatter = DateTimeFormat.forPattern("HH:mm dd/MM/YYYY")

  def parseDateTime(dateString: String) = {

    println(s"Trying to parse date '$dateString'")
    pattern.parseDateTime(dateString)
  }

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

  def optDate(s: String) = if (s.isEmpty) None else Option(s)

  def optInt(s: String) = if (s.isEmpty) None else Option(s.toInt)

  lazy val lhrFlights: Iterator[Try[LHRLiveFlight]] = {

    csvLines.zipWithIndex.drop(1).map { case (l, idx) =>

      val t = Try {
        val splitRow: Array[String] = l.substring(1, l.length - 1).split("\",\"")
        println(s"length ${splitRow.length} $l")
        val sq: (String) => String = (x) => x
        LHRLiveFlight(
          term = s"T${sq(splitRow(0))}",
          flightCode = sq(splitRow(1)),
          operator = sq(splitRow(2)),
          from = sq(splitRow(3)),
          airportName = sq(splitRow(4)),
          scheduled = pd(splitRow(5)),
          estimated = opt(splitRow(6)),
          touchdown = opt(splitRow(7)),
          estChox = opt(splitRow(8)),
          actChox = opt(sq(splitRow(9))),
          stand = opt(splitRow(10)),
          maxPax = optInt(splitRow(11)),
          actPax = optInt(splitRow(12)),
          connPax = optInt(splitRow(13)))
      }
      t match {
        case Success(s) => Success(s)
        case Failure(t) =>
          println(s"The failure is $t")
          Failure(LHRCsvException(l, idx, t))
      }
    }
  }
  val walkTimeMinutes = 4

  lazy val successfulFlights = lhrFlights.collect { case Success(s) => s }

  lazy val copiedToApiFlights: Source[List[ApiFlight], NotUsed] = Source(
    List(
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
  self: AirportConfiguration =>
  val splitProviders = List(SplitsProvider.csvProvider, SplitsProvider.defaultProvider(airportConfig))
}

class Application @Inject()(
                             implicit val config: Configuration,
                             implicit val mat: Materializer,
                             env: Environment,
                             override val system: ActorSystem,
                             ec: ExecutionContext
                           )
  extends Controller with Core with AirportConfProvider with ProdPassengerSplitProviders with SystemActors {
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

  val fetcher = mockProd match {
    case "MOCK" => MockChroma(system)
    case "PROD" => ProdChroma(system)
  }

  val copiedToApiFlights: Source[Flights, Cancellable] = portCode match {
    case "EDI" =>
      ChromaFlightFeed(log, fetcher).chromaEdiFlights().map(Flights(_))
    case "LHR" =>
      LHRFlightFeed().map(Flights(_))
    case _ =>
      ChromaFlightFeed(log, fetcher).chromaVanillaFlights().map(Flights(_))
  }

  copiedToApiFlights.runWith(Sink.actorRef(flightsActor, OnComplete))

  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }

  def autowireApi(path: String) = Action.async(parse.raw) {
    implicit request =>
      println(s"Request path: $path")

      // get the request body as ByteString
      val b = request.body.asBytes(parse.UNLIMITED).get

      // call Autowire route
      Router.route[Api](createApiService)(
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


