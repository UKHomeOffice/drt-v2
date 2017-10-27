package services.crunch

import actors.GetState
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import controllers.SystemActors.SplitsProvider
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages.Crunch.{earliestAndLatestAffectedPcpTimeFromFlights, getLocalLastMidnight, getLocalNextMidnight}
import services.graphstages._
import services.{ArrivalsState, ForecastBaseArrivalsActor, LiveArrivalsActor, SDate}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.util.{Failure, Success}


case class LiveSimulationInputs(crunch: SourceQueueWithComplete[PortState],
                                shifts: SourceQueueWithComplete[String],
                                fixedPoints: SourceQueueWithComplete[String],
                                staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                                actualDeskStats: SourceQueueWithComplete[ActualDeskStats])

case class ForecastSimulationInputs(crunch: SourceQueueWithComplete[PortState],
                                    shifts: SourceQueueWithComplete[String],
                                    fixedPoints: SourceQueueWithComplete[String],
                                    staffMovements: SourceQueueWithComplete[Seq[StaffMovement]])

case class LiveCrunchInputs(arrivals: SourceQueueWithComplete[ArrivalsDiff], manifests: SourceQueueWithComplete[VoyageManifests])

case class ForecastCrunchInputs(arrivals: SourceQueueWithComplete[ArrivalsDiff])

case class ArrivalsInputs(base: SourceQueueWithComplete[Flights], live: SourceQueueWithComplete[Flights])

case class CrunchSystem(shifts: List[SourceQueueWithComplete[String]],
                        fixedPoints: List[SourceQueueWithComplete[String]],
                        staffMovements: List[SourceQueueWithComplete[Seq[StaffMovement]]],
                        baseArrivals: SourceQueueWithComplete[Flights],
                        liveArrivals: SourceQueueWithComplete[Flights],
                        actualDeskStats: SourceQueueWithComplete[ActualDeskStats],
                        manifests: SourceQueueWithComplete[VoyageManifests]
                       )

object CrunchSystem {

  case class CrunchProps(system: ActorSystem,
                         airportConfig: AirportConfig,
                         pcpArrival: Arrival => MilliDate,
                         historicalSplitsProvider: SplitsProvider,
                         liveCrunchStateActor: ActorRef,
                         forecastCrunchStateActor: ActorRef,
                         maxDaysToCrunch: Int,
                         expireAfterMillis: Long)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(props: CrunchProps): CrunchSystem = {

    val baseArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "base-arrivals-actor")
    val liveArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

    val askableLiveCrunchStateActor: AskableActorRef = props.liveCrunchStateActor
    val askableForecastCrunchStateActor: AskableActorRef = props.forecastCrunchStateActor

    val initialLiveCrunchState = Await.result(askableLiveCrunchStateActor.ask(GetState)(new Timeout(1 minute)).map {
      case ps: PortState => Option(ps)
      case _ => None
    }, 1 minute)
    val initialForecastCrunchState = Await.result(askableForecastCrunchStateActor.ask(GetState)(new Timeout(1 minute)).map {
      case ps: PortState => Option(ps)
      case _ => None
    }, 1 minute)

    def staffingStage(name: String, initialPortState: Option[PortState], crunchEnd: SDateLike => SDateLike) = new StaffingStage(
      name = name,
      initialOptionalPortState = initialPortState,
      minMaxDesks = props.airportConfig.minMaxDesksByTerminalQueue,
      slaByQueue = props.airportConfig.slaByQueue,
      warmUpMinutes = 120,
      now = () => SDate.now(),
      expireAfterMillis = props.expireAfterMillis,
      crunchEnd = crunchEnd
    )

    val actualDesksAndQueuesStage = new ActualDesksAndWaitTimesGraphStage()

    val liveSimInputs: LiveSimulationInputs = startRunnableLiveSimulation(
      system = props.system,
      crunchStateActor = props.liveCrunchStateActor,
      staffingStage = staffingStage("live", initialLiveCrunchState, (minute: SDateLike) => getLocalNextMidnight(minute)),
      actualDesksStage = actualDesksAndQueuesStage)

    val liveCrunchInputs: LiveCrunchInputs = startRunnableLiveCrunch(
      system = props.system,
      simulationSubscriber = liveSimInputs.crunch,
      airportConfig = props.airportConfig,
      historicalSplitsProvider = props.historicalSplitsProvider,
      expireAfterMillis = props.expireAfterMillis)

    val forecastSimInputs: ForecastSimulationInputs = startRunnableForecastSimulation(
      system = props.system,
      crunchStateActor = props.forecastCrunchStateActor,
      staffingStage = staffingStage("forecast", initialForecastCrunchState, (minute: SDateLike) => getLocalNextMidnight(minute)))

    val forecastCrunchInputs: ForecastCrunchInputs = startRunnableForecastCrunch(
      system = props.system,
      simulationSubscriber = forecastSimInputs.crunch,
      crunchStateActor = baseArrivalsActor,
      maxDaysToCrunch = props.maxDaysToCrunch,
      airportConfig = props.airportConfig,
      historicalSplitsProvider = props.historicalSplitsProvider,
      expireAfterMillis = props.expireAfterMillis)

    val arrivalsInputs: ArrivalsInputs = startRunnableArrivals(system = props.system, crunchSubscribers = List(liveCrunchInputs.arrivals, forecastCrunchInputs.arrivals), baseArrivalsActor = baseArrivalsActor, liveArrivalsActor = liveArrivalsActor, pcpArrival = props.pcpArrival, airportConfig = props.airportConfig, 2 * Crunch.oneDayMillis)

    CrunchSystem(
      shifts = List(liveSimInputs.shifts, forecastSimInputs.shifts),
      fixedPoints = List(liveSimInputs.fixedPoints, forecastSimInputs.fixedPoints),
      staffMovements = List(liveSimInputs.staffMovements, forecastSimInputs.staffMovements),
      baseArrivals = arrivalsInputs.base,
      liveArrivals = arrivalsInputs.live,
      actualDeskStats = liveSimInputs.actualDeskStats,
      manifests = liveCrunchInputs.manifests
    )
  }

  def arrivalsStage(baseArrivalsActor: ActorRef, liveArrivalsActor: ActorRef, pcpArrival: Arrival => MilliDate, airportConfig: AirportConfig, expireAfterMillis: Long) = new ArrivalsGraphStage(
    initialBaseArrivals = initialArrivals(baseArrivalsActor),
    initialLiveArrivals = initialArrivals(liveArrivalsActor),
    baseArrivalsActor = baseArrivalsActor,
    liveArrivalsActor = liveArrivalsActor,
    pcpArrivalTime = pcpArrival,
    validPortTerminals = airportConfig.terminalNames.toSet,
    expireAfterMillis = expireAfterMillis,
    now = () => SDate.now())

  def crunchStage(name: String, maxDays: Int, manifestsUsed: Boolean = true, airportConfig: AirportConfig, historicalSplitsProvider: SplitsProvider, expireAfterMillis: Long): CrunchGraphStage = new CrunchGraphStage(
    name = name,
    optionalInitialFlights = None,
    slas = airportConfig.slaByQueue,
    minMaxDesks = airportConfig.minMaxDesksByTerminalQueue,
    procTimes = airportConfig.defaultProcessingTimes.head._2,
    groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
    portSplits = airportConfig.defaultPaxSplits,
    csvSplitsProvider = historicalSplitsProvider,
    crunchStartFromFirstPcp = getLocalLastMidnight,
    crunchEndFromLastPcp = (maxPcpTime: SDateLike) => getLocalNextMidnight(maxPcpTime),
    expireAfterMillis = expireAfterMillis,
    now = () => SDate.now(),
    maxDaysToCrunch = maxDays,
    earliestAndLatestAffectedPcpTime = earliestAndLatestAffectedPcpTimeFromFlights(maxDays = maxDays),
    manifestsUsed = manifestsUsed)

  def startRunnableLiveSimulation(implicit system: ActorSystem, crunchStateActor: ActorRef, staffingStage: StaffingStage, actualDesksStage: ActualDesksAndWaitTimesGraphStage): LiveSimulationInputs = {
    val crunchSource: Source[PortState, SourceQueueWithComplete[PortState]] = Source.queue[PortState](10, OverflowStrategy.backpressure)
    val shiftsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](10, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](10, OverflowStrategy.backpressure)

    val (crunchInput, shiftsInput, fixedPointsInput, staffMovementsInput, actualDesksAndQueuesInput) = RunnableLiveSimulationGraph(
      crunchStateActor = crunchStateActor,
      crunchSource = crunchSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
      staffingStage = staffingStage,
      actualDesksStage = actualDesksStage
    ).run()(ActorMaterializer())

    LiveSimulationInputs(crunchInput, shiftsInput, fixedPointsInput, staffMovementsInput, actualDesksAndQueuesInput)
  }

  def startRunnableForecastSimulation(implicit system: ActorSystem, crunchStateActor: ActorRef, staffingStage: StaffingStage): ForecastSimulationInputs = {
    val crunchSource: Source[PortState, SourceQueueWithComplete[PortState]] = Source.queue[PortState](10, OverflowStrategy.backpressure)
    val shiftsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](10, OverflowStrategy.backpressure)

    val (crunchInput, shiftsInput, fixedPointsInput, staffMovementsInput) = RunnableForecastSimulationGraph(
      crunchStateActor = crunchStateActor,
      crunchSource = crunchSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      staffingStage = staffingStage
    ).run()(ActorMaterializer())

    ForecastSimulationInputs(crunchInput, shiftsInput, fixedPointsInput, staffMovementsInput)
  }

  def startRunnableLiveCrunch(implicit system: ActorSystem, simulationSubscriber: SourceQueueWithComplete[PortState], airportConfig: AirportConfig, historicalSplitsProvider: SplitsProvider, expireAfterMillis: Long): LiveCrunchInputs = {
    val manifestsSource: Source[VoyageManifests, SourceQueueWithComplete[VoyageManifests]] = Source.queue[VoyageManifests](100, OverflowStrategy.backpressure)
    val liveArrivalsDiffQueueSource: Source[ArrivalsDiff, SourceQueueWithComplete[ArrivalsDiff]] = Source.queue[ArrivalsDiff](0, OverflowStrategy.backpressure)
    val (liveArrivalsCrunchInput, manifestsInput) = RunnableLiveCrunchGraph[SourceQueueWithComplete[ArrivalsDiff], SourceQueueWithComplete[VoyageManifests]](
      arrivalsSource = liveArrivalsDiffQueueSource,
      voyageManifestsSource = manifestsSource,
      cruncher = crunchStage(name = "live", maxDays = 2, airportConfig = airportConfig, historicalSplitsProvider = historicalSplitsProvider, expireAfterMillis = expireAfterMillis),
      simulationQueueSubscriber = simulationSubscriber
    ).run()(ActorMaterializer())

    LiveCrunchInputs(liveArrivalsCrunchInput, manifestsInput)
  }

  def startRunnableForecastCrunch(implicit system: ActorSystem, simulationSubscriber: SourceQueueWithComplete[PortState], crunchStateActor: ActorRef, maxDaysToCrunch: Int, airportConfig: AirportConfig, historicalSplitsProvider: SplitsProvider, expireAfterMillis: Long): ForecastCrunchInputs = {
    val forecastArrivalsDiffQueueSource: Source[ArrivalsDiff, SourceQueueWithComplete[ArrivalsDiff]] = Source.queue[ArrivalsDiff](0, OverflowStrategy.backpressure)
    val forecastArrivalsCrunchInput: SourceQueueWithComplete[ArrivalsDiff] = RunnableForecastCrunchGraph[SourceQueueWithComplete[ArrivalsDiff]](
      arrivalsSource = forecastArrivalsDiffQueueSource,
      cruncher = crunchStage(name = "forecast", maxDays = maxDaysToCrunch, manifestsUsed = false, airportConfig = airportConfig, historicalSplitsProvider, expireAfterMillis = expireAfterMillis),
      simulationQueueSubscriber = simulationSubscriber
    ).run()(ActorMaterializer())

    ForecastCrunchInputs(forecastArrivalsCrunchInput)
  }

  def startRunnableArrivals(implicit system: ActorSystem, crunchSubscribers: List[SourceQueueWithComplete[ArrivalsDiff]], baseArrivalsActor: ActorRef, liveArrivalsActor: ActorRef, pcpArrival: Arrival => MilliDate, airportConfig: AirportConfig, expireAfterMillis: Long): ArrivalsInputs = {
    val baseArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](10, OverflowStrategy.backpressure)
    val liveArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](10, OverflowStrategy.backpressure)

    val (baseArrivalsInput, liveArrivalsInput) = RunnableArrivalsGraph[SourceQueueWithComplete[Flights]](
      baseArrivals,
      liveArrivals,
      arrivalsStage(baseArrivalsActor = baseArrivalsActor, liveArrivalsActor = liveArrivalsActor, pcpArrival, airportConfig, expireAfterMillis),
      crunchSubscribers
    ).run()(ActorMaterializer())

    ArrivalsInputs(baseArrivalsInput, liveArrivalsInput)
  }

  def initialArrivals(arrivalsActor: AskableActorRef): Set[Arrival] = {
    val canWaitMinutes = 5
    val arrivalsFuture: Future[Set[Arrival]] = arrivalsActor.ask(GetState)(new Timeout(canWaitMinutes minutes)).map {
      case ArrivalsState(arrivals) => arrivals.values.toSet
      case _ => Set[Arrival]()
    }
    arrivalsFuture.onComplete {
      case Success(arrivals) => arrivals
      case Failure(t) =>
        log.warn(s"Failed to get an initial ArrivalsState: $t")
        Set[Arrival]()
    }
    Await.result(arrivalsFuture, canWaitMinutes minutes)
  }
}
