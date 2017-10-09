package controllers

import java.nio.ByteBuffer

import actors._
import actors.pointInTime.CrunchStateReadActor
import akka.actor._
import akka.event.LoggingAdapter
import akka.pattern.{AskableActorRef, _}
import akka.stream._
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.util.{ByteString, Timeout}
import boopickle.Default._
import com.google.inject.Inject
import com.typesafe.config.ConfigFactory
import controllers.SystemActors.SplitsProvider
import drt.server.feeds.chroma.{ChromaFlightFeed, MockChroma, ProdChroma}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.shared.Crunch.{CrunchState, CrunchUpdates, MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, TerminalName}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{AirportConfig, Api, Arrival, _}
import net.schmizz.sshj.sftp.SFTPClient
import org.joda.time.chrono.ISOChronology
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.http.HttpEntity
import play.api.mvc._
import play.api.{Configuration, Environment}
import server.feeds.acl.AclFeed._
import services.PcpArrival._
import services.SDate.implicits._
import services.SplitsProvider.SplitProvider
import services.{SDate, _}
import services.graphstages.Crunch.{getLocalLastMidnight, midnightThisMorning, oneHourMillis}
import services.graphstages._
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.PaxTypeAndQueueCount

import scala.collection.immutable.{IndexedSeq, Map}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}
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


  val ftpServer = ConfigFactory.load.getString("acl.host")
  val username = ConfigFactory.load.getString("acl.username")
  val path = ConfigFactory.load.getString("acl.keypath")

  val sftp: SFTPClient = sftpClient(ftpServer, username, path)
  val aclArrivals = arrivalsFromCsvContent(contentFromFileName(sftp, latestFileForPort(sftp, airportConfig.portCode)))

  system.log.info(s"Path to splits file ${ConfigFactory.load.getString("passenger_splits_csv_url")}")


  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.gates_csv_url"))
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(ConfigFactory.load.getString("walk_times.stands_csv_url"))

  def walkTimeProvider(flight: Arrival): Millis =
    gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, airportConfig.defaultWalkTimeMillis)(flight)

  def pcpArrivalTimeCalculator: (Arrival) => MilliDate =
    PaxFlow.pcpArrivalTimeForFlight(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis)(walkTimeProvider)

  val actorMaterializer = ActorMaterializer()

  val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "base-arrivals-actor")
  val askableBaseArrivalsActor: AskableActorRef = baseArrivalsActor
  val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")
  val askableLiveArrivalsActor: AskableActorRef = liveArrivalsActor
  val crunchStateActor: ActorRef = system.actorOf(Props(classOf[CrunchStateActor], airportConfig.queues), name = "crunch-state-actor")
  val askableCrunchStateActor: AskableActorRef = crunchStateActor
  val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor]), name = "voyage-manifests-actor")

  val chroma = ChromaFlightFeed(system.log, ProdChroma(system))

  val manifestsSource: Source[VoyageManifests, SourceQueueWithComplete[VoyageManifests]] = Source.queue[VoyageManifests](100, OverflowStrategy.backpressure)
  val shiftsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](100, OverflowStrategy.backpressure)
  val fixedPointsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](100, OverflowStrategy.backpressure)
  val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](100, OverflowStrategy.backpressure)
  val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](100, OverflowStrategy.backpressure)

  val crunchStateFuture: Future[Option[PortState]] = askableCrunchStateActor.ask(GetState)(new Timeout(1 minute)).map {
    case Some(ps: PortState) => Option(ps)
    case _ => None
  }

  crunchStateFuture.onComplete {
    case Success(Some(cs)) => Option(cs)
    case Success(None) => None
    case Failure(t) =>
      log.warn(s"Failed to get an initial CrunchState: $t")
      None
  }

  val baseArrivalsFuture: Future[Set[Arrival]] = askableBaseArrivalsActor.ask(GetState)(new Timeout(1 minute)).map {
    case ArrivalsState(arrivals) => arrivals.values.toSet
    case _ => Set[Arrival]()
  }

  baseArrivalsFuture.onComplete {
    case Success(arrivals) => arrivals
    case Failure(t) =>
      log.warn(s"Failed to get an initial base ArrivalsState: $t")
      Set[Arrival]()
  }

  val liveArrivalsFuture: Future[Set[Arrival]] = askableLiveArrivalsActor.ask(GetState)(new Timeout(1 minute)).map {
    case ArrivalsState(arrivals) => arrivals.values.toSet
    case _ => Set[Arrival]()
  }

  liveArrivalsFuture.onComplete {
    case Success(arrivals) => arrivals
    case Failure(t) =>
      log.warn(s"Failed to get an initial live ArrivalsState: $t")
      Set[Arrival]()
  }
  val initialBaseArrivals: Set[Arrival] = Await.result(baseArrivalsFuture, 1 minute)
  val initialLiveArrivals: Set[Arrival] = Await.result(liveArrivalsFuture, 1 minute)

  log.info(s"Awaiting CrunchStateActor response")
  val optionalCrunchState: Option[PortState] = Await.result(crunchStateFuture, 1 minute)
  log.info(s"Got CrunchStateActor response")

  val crunchFlow: CrunchGraphStage = new CrunchGraphStage(
    optionalInitialFlights = optionalCrunchState.map(cs => FlightsWithSplits(cs.flights.values.toList)),
    slas = airportConfig.slaByQueue,
    minMaxDesks = airportConfig.minMaxDesksByTerminalQueue,
    procTimes = airportConfig.defaultProcessingTimes.head._2,
    groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
    portSplits = airportConfig.defaultPaxSplits,
    csvSplitsProvider = historicalSplitsProvider,
    crunchStartDateProvider = midnightThisMorning _)

  val staffingGraphStage = new StaffingStage(optionalCrunchState, airportConfig.minMaxDesksByTerminalQueue, airportConfig.slaByQueue)
  val actualDesksAndQueuesStage = new ActualDesksAndWaitTimesGraphStage()

  val arrivalsStage = new ArrivalsGraphStage(
    initialBaseArrivals = initialBaseArrivals,
    initialLiveArrivals = initialLiveArrivals,
    baseArrivalsActor = baseArrivalsActor,
    liveArrivalsActor = liveArrivalsActor,
    pcpArrivalTime = pcpArrivalTimeCalculator,
    validPortTerminals = airportConfig.terminalNames.toSet)
  val baseArrivalsQueueSource: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](0, OverflowStrategy.backpressure)
  val liveArrivalsQueueSource: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](0, OverflowStrategy.backpressure)

  val (baseArrivalsInput, liveArrivalsInput, manifestsInput, shiftsInput, fixedPointsInput, staffMovementsInput, actualDesksAndQueuesInput) =
    RunnableCrunchGraph[
      SourceQueueWithComplete[Flights],
      SourceQueueWithComplete[VoyageManifests],
      SourceQueueWithComplete[String],
      SourceQueueWithComplete[String],
      SourceQueueWithComplete[Seq[StaffMovement]],
      SourceQueueWithComplete[ActualDeskStats]
      ](
      baseArrivalsSource = baseArrivalsQueueSource,
      liveArrivalsSource = liveArrivalsQueueSource,
      voyageManifestsSource = manifestsSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
      staffingStage = staffingGraphStage,
      arrivalsStage = arrivalsStage,
      cruncher = crunchFlow,
      actualDesksStage = actualDesksAndQueuesStage,
      crunchStateActor = crunchStateActor
    ).run()(actorMaterializer)

  flightsSource(mockProd, airportConfig.portCode).runForeach(f => liveArrivalsInput.offer(f))(actorMaterializer)
  baseArrivalsInput.offer(Flights(aclArrivals))

  val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor], shiftsInput))
  val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor], fixedPointsInput))

  if (portCode == "LHR") config.getString("lhr.blackjack_url").map(csvUrl => {
    val threeMinutesInterval = 3 * 60 * 1000

    Deskstats.startBlackjack(csvUrl, actualDesksAndQueuesInput, threeMinutesInterval milliseconds, SDate.now().addDays(-1))
  })

  val bucket: String = config.getString("atmos.s3.bucket").getOrElse(throw new Exception("You must set ATMOS_S3_BUCKET for us to poll for AdvPaxInfo"))
  val atmosHost: String = config.getString("atmos.s3.url").getOrElse(throw new Exception("You must set ATMOS_S3_URL"))
  val advPaxInfoProvider = VoyageManifestsProvider(atmosHost, bucket, airportConfig.portCode, manifestsInput, voyageManifestsActor)
  advPaxInfoProvider.start()

  val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor], staffMovementsInput))

  def historicalSplitsProvider: SplitsProvider = SplitsProvider.csvProvider

  //  def flightWalkTimeProvider(flight: Arrival): Millis

  def flightsSource(prodMock: String, portCode: String): Source[Flights, Cancellable] = {
    val feed = portCode match {
      case "LHR" =>
        LHRFlightFeed()
      case "EDI" =>
        createChromaFlightFeed(prodMock).chromaEdiFlights()
      case _ =>
        createChromaFlightFeed(prodMock).chromaVanillaFlights()
    }
    feed.map(Flights)
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

  val csvSplitsProvider: (Arrival) => Option[SplitRatios] = SplitsProvider.csvProvider

  def egatePercentageProvider(apiFlight: Arrival): Double = {
    CSVPassengerSplitsProvider.egatePercentageFromSplit(csvSplitsProvider(apiFlight), 0.6)
  }

  def fastTrackPercentageProvider(apiFlight: Arrival): Option[FastTrackPercentages] =
    Option(CSVPassengerSplitsProvider.fastTrackPercentagesFromSplit(csvSplitsProvider(apiFlight), 0d, 0d))

  private implicit val timeout = Timeout(250 milliseconds)
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

  val createApiService = new ApiService(airportConfig, shiftsActor, fixedPointsActor, staffMovementsActor) {

    override implicit val timeout: Timeout = Timeout(5 seconds)

    def actorSystem: ActorSystem = system

    def getCrunchState(pointIntTime: MillisSinceEpoch): Future[Option[CrunchState]] = {
      if (pointIntTime > 0) {
        crunchStateAtPointInTime(pointIntTime)
      } else {
        val startMillis = midnightThisMorning - oneHourMillis * 3
        val endMillis = midnightThisMorning + oneHourMillis * 30
        val crunchStateFuture = crunchStateActor.ask(GetPortState(startMillis, endMillis))(new Timeout(5 seconds))

        crunchStateFuture.map {
          case Some(PortState(f, m)) => Option(CrunchState(0L, 0, f.values.toSet, m.values.toSet))
          case _ => None
        } recover {
          case t =>
            log.warn(s"Didn't get a CrunchState: $t")
            None
        }
      }
    }

    def getCrunchUpdates(sinceMillis: MillisSinceEpoch): Future[Option[CrunchUpdates]] = {
      val startMillis = midnightThisMorning - oneHourMillis * 3
      val endMillis = midnightThisMorning + oneHourMillis * 30
      val crunchStateFuture = crunchStateActor.ask(GetUpdatesSince(sinceMillis, startMillis, endMillis))(new Timeout(5 seconds))

      crunchStateFuture.map {
        case Some(cu: CrunchUpdates) => Option(cu)
        case _ => None
      } recover {
        case t =>
          log.warn(s"Didn't get a CrunchUpdates: $t")
          None
      }
    }

    override def askableCacheActorRef: AskableActorRef = cacheActorRef

    override def crunchStateActor: AskableActorRef = ctrl.crunchStateActor
  }

  def index = Action {
    Ok(views.html.index("DRT - BorderForce"))
  }

  def crunchStateAtPointInTime(pointInTime: MillisSinceEpoch): Future[Option[CrunchState]] = {
    val relativeLastMidnight = getLocalLastMidnight(SDate(pointInTime)).millisSinceEpoch
    val startMillis = relativeLastMidnight - oneHourMillis * 3
    val endMillis = relativeLastMidnight + oneHourMillis * 30
    val query = CachableActorQuery(Props(classOf[CrunchStateReadActor], SDate(pointInTime), airportConfig.queues), GetPortState(startMillis, endMillis))
    val portCrunchResult = cacheActorRef.ask(query)(new Timeout(30 seconds))
    portCrunchResult.map {
      case Some(PortState(f, m)) => Option(CrunchState(0L, 0, f.values.toSet, m.values.toSet))
      case _ => None
    }.recover {
      case t =>
        log.warning(s"Didn't get a point-in-time CrunchState: $t")
        None
    }
  }

  def getDesksAndQueuesCSV(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    implicit val timeout: Timeout = Timeout(5 seconds)

    val crunchStateFuture: Future[Option[CrunchState]] = crunchStateAtPointInTime(pointInTime.toLong)

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

  def getFlightsWithSplitsCSV(pointInTime: String, terminalName: TerminalName): Action[AnyContent] = Action.async {
    implicit val timeout: Timeout = Timeout(60 seconds)

    val potMilliDate = MilliDate(pointInTime.toLong)
    val crunchStateFuture = crunchStateAtPointInTime(pointInTime.toLong)
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

  def autowireApi(path: String): Action[RawBuffer] = Action.async(parse.raw) {
    implicit request =>
      log.debug(s"Request path: $path")

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
