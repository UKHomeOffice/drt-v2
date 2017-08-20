package controllers

import java.nio.ByteBuffer
import java.util.UUID

import actors._
import akka.Done
import akka.actor._
import akka.pattern.{AskableActorRef, _}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import passengersplits.core.PassengerInfoRouterActor.{ReportVoyagePaxSplit, ReportVoyagePaxSplitBetween}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import services.Crunch.{CrunchStateFlow, Publisher}

import scala.collection.immutable
import scala.collection.immutable.Map
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
//import controllers.Deskstats.log
import controllers.SystemActors.SplitsProvider
import drt.chroma.chromafetcher.ChromaFetcher
import drt.http.ProdSendAndReceive
import drt.server.feeds.chroma.{ChromaFlightFeed, MockChroma, ProdChroma}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{AirportConfig, Api, Arrival, CrunchResult, _}
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.chrono.ISOChronology
import passengersplits.core.AdvancePassengerInfoActor
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

object PaxFlow {
  def makeFlightPaxFlowCalculator(splitRatioForFlight: (Arrival) => Option[SplitRatios],
                                  bestPax: (Arrival) => Int): (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val provider = PaxLoadCalculator.flightPaxFlowProvider(splitRatioForFlight, bestPax)
    (arrival) => {
      val pax = bestPax(arrival)
      val paxFlow = provider(arrival)
      val summedPax = paxFlow.map(_._2.paxSum).sum
      val firstPaxTime = paxFlow.headOption.map(pf => SDate(pf._1).toString)
      log.debug(s"${Arrival.summaryString(arrival)} pax: $pax, summedFlowPax: $summedPax, deltaPax: ${pax - summedPax}, firstPaxTime: ${firstPaxTime}")
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

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")

  val pcpArrivalTimeCalculator = PaxFlow.pcpArrivalTimeForFlight(airportConfig)(flightWalkTimeProvider) _

  val paxFlowCalculator: (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
    PaxFlow.makeFlightPaxFlowCalculator(PaxFlow.splitRatioForFlight(splitsProviders), BestPax(airportConfig.portCode))


  val flightPassengerSplitReporter = system.actorOf(Props[AdvancePassengerInfoActor], name = "flight-pax-reporter")
  val crunchStateActor = system.actorOf(Props(classOf[CrunchStateActor], airportConfig.queues), name = "crunch-state-actor")

  val actorMaterialiser = ActorMaterializer()

  implicit val actorSystem = system
  def crunchFlow = new CrunchStateFlow(
    airportConfig.slaByQueue,
    airportConfig.minMaxDesksByTerminalQueue,
    airportConfig.defaultProcessingTimes.head._2,
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
    airportConfig.terminalNames.toSet)

  val flightsActorProps = Props(
    classOf[FlightsActor],
    crunchStateActor,
    flightPassengerSplitReporter,
    Publisher(crunchStateActor, crunchFlow)(actorMaterialiser),
    csvSplitsProvider,
    BestPax(portCode),
    pcpArrivalTimeCalculator, airportConfig
  )
  val flightsActor: ActorRef = system.actorOf(flightsActorProps, "flightsActor")
  val crunchByAnotherName: ActorSelection = system.actorSelection("crunchActor")
  val flightsActorAskable: AskableActorRef = flightsActor
  val actualDesksActor: ActorRef = system.actorOf(Props[DeskstatsActor])

  def csvSplitsProvider: SplitsProvider

  def splitsProviders(): List[SplitsProvider]

  def flightWalkTimeProvider(flight: Arrival): Millis
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

  import scala.concurrent.ExecutionContext.global

  override val csvSplitsProvider: (Arrival) => Option[SplitRatios] = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: Arrival): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: Arrival): Option[FastTrackPercentages] = Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight), 0d, 0d))

  private implicit val timeout = Timeout(101 milliseconds)

  def apiSplitsProv(flight: Arrival): Option[SplitRatios] =
    AdvPaxSplitsProvider.splitRatioProviderWithCsvPercentages(
      airportConfig.portCode)(
      flightPassengerSplitReporter)(
      egatePercentageProvider,
      fastTrackPercentageProvider
    )(flight)(timeout, global)

  val apiSplitsProvider: (Arrival) => Option[SplitRatios] = (flight: Arrival) => apiSplitsProv(flight)
  override val splitsProviders = List(apiSplitsProvider, csvSplitsProvider, SplitsProvider.defaultProvider(airportConfig))
}

trait ProdWalkTimesProvider {
  self: AirportConfProvider =>
  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))

  def flightWalkTimeProvider(flight: Arrival): Millis = gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, airportConfig.defaultWalkTimeMillis)(flight)
}

trait ImplicitTimeoutProvider {
  implicit val timeout = Timeout(102 milliseconds)
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
  val log = system.log

  log.info(s"ISOChronology.getInstance: ${ISOChronology.getInstance}")
  private val systemTimeZone = System.getProperty("user.timezone")
  log.info(s"System.getProperty(user.timezone): ${systemTimeZone}")
  assert(systemTimeZone == "UTC")

  val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = ctrl.system
  }

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
    override def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = paxFlowCalculator(flight)

    override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = airportConfig.defaultProcessingTimes(terminalName)(paxTypeAndQueue)

    override implicit val timeout: Timeout = Timeout(5 seconds)

    def actorSystem: ActorSystem = system

    override def flightPassengerReporter: ActorRef = ctrl.flightPassengerSplitReporter

    override def getActualDeskStats(): Future[ActualDeskStats] = {
      val futureDesks = actualDesksActor ? GetActualDeskStats()
      futureDesks.map(_.asInstanceOf[ActualDeskStats])
    }
  }

  trait CrunchFromCrunchState {
    implicit val timeout: Timeout = Timeout(103 milliseconds)
    val crunchStateActor: AskableActorRef = ctrl.crunchStateActor

    def getTerminalCrunchResult(terminalName: TerminalName): Future[List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]] = {
      val terminalCrunchResult = crunchStateActor ? GetTerminalCrunch(terminalName)
      terminalCrunchResult.map {
        case Nil => List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]()
        case qrcs: List[(QueueName, Either[NoCrunchAvailable, CrunchResult])] => qrcs
      }
    }
  }

  trait GetFlightsFromActor extends FlightsService {
    override def getFlights(start: Long, end: Long): Future[List[Arrival]] = {
      val flights: Future[Any] = ctrl.flightsActorAskable.ask(GetFlights)(Timeout(1 second))
      val fsFuture = flights.collect {
        case Flights(fs) => fs
      }
      fsFuture
    }

    override def getFlightsWithSplits(start: Long, end: Long): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
      if(start > 0) {
        getFlightsWithSplitsAtDate(start)
      } else {
        val askable = ctrl.crunchStateActor
        log.info(s"asking $askable for flightsWithSplits")
        val flights = askable.ask(GetFlights)(Timeout(100 milliseconds))
        flights.recover {
          case e => FlightsNotReady()
        }.map {
          case FlightsNotReady() => Left(FlightsNotReady())
          case FlightsWithSplits(flights) => Right(FlightsWithSplits(flights))
        }
      }
    }

    override def getFlightsWithSplitsAtDate(pointInTime: MillisSinceEpoch): Future[Either[FlightsNotReady, FlightsWithSplits]] = {
      val crunchStateReadActorProps = Props(classOf[CrunchStateReadActor], SDate(pointInTime), airportConfig.queues)
      val crunchStateReadActor: ActorRef = system.actorOf(crunchStateReadActorProps, "crunchStateReadActor" + UUID.randomUUID().toString)

      log.info(s"asking $crunchStateReadActor for flightsWithSplits")
      val flights = crunchStateReadActor.ask(GetFlights)(Timeout(3000 milliseconds))

      flights.recover {
        case e: Throwable =>
          log.info(s"GetFlightsWithSplits failed: $e")
          Left(FlightsNotReady())
      }.map {
        case fs: FlightsWithSplits =>
          log.info(s"GetFlightsWithSplits success: ${fs.flights.length} flights")
          Right(fs)
        case e =>
          log.info(s"GetFlightsWithSplits failed: $e")
          Left(FlightsNotReady())
      }
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

  def splits(fromDate: String, toDate: String) = Action.async {
    implicit request =>
      def manifestPassengerToCSV(m: VoyageManifest, p: PassengerInfoJson) = {
        s""""${m.EventCode}","${m.ArrivalPortCode}","${m.DeparturePortCode}","${m.VoyageNumber}","${m.CarrierCode}","${m.ScheduledDateOfArrival}","${m.ScheduledTimeOfArrival}","${p.NationalityCountryCode.getOrElse("")}","${p.DocumentType.getOrElse("")}","${p.EEAFlag}","${p.InTransitFlag}","${p.DocumentIssuingCountryCode}","${p.DisembarkationPortCode.getOrElse("")}","${p.DisembarkationPortCountryCode.getOrElse("")}","${p.Age.getOrElse("")}""""
      }

      def headings = """"Event Code","Arrival Port Code","Departure Port Code","Voyage Number","Carrier Code","Scheduled Date","Scheduled Time","Nationality Country Code","Document Type","EEA Flag","In Transit Flag","Document Issuing Country Code","Disembarkation Port Code","Disembarkation Country Code","Age""""

      def passengerCsvLines(result: List[VoyageManifest]) = {
        for {
          manifest <- result
          passenger <- manifest.PassengerList
        } yield
          manifestPassengerToCSV(manifest, passenger)
      }

      val voyageManifestsFuture = ctrl.flightPassengerSplitReporter ? ReportVoyagePaxSplitBetween(SDate(fromDate), SDate(toDate))
      voyageManifestsFuture.map {
        case result: List[VoyageManifest] => Ok(headings + "\n" + passengerCsvLines(result).mkString("\n"))
      }
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

case class GetTerminalCrunch(terminalName: TerminalName)
