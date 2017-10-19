package services.crunch

import actors.GetState
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import controllers.SystemActors.SplitsProvider
import drt.shared.Crunch.PortState
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages.Crunch.{earliestAndLatestAffectedPcpTimeFromFlights, getLocalLastMidnight, getLocalNextMidnight}
import services.graphstages._
import services.{ArrivalsState, ForecastBaseArrivalsActor, LiveArrivalsActor}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.util.{Failure, Success}


case class SimulationInputs(crunch: SourceQueueWithComplete[PortState],
                            shifts: SourceQueueWithComplete[String],
                            fixedPoints: SourceQueueWithComplete[String],
                            staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                            actualDeskStats: SourceQueueWithComplete[ActualDeskStats])

case class LiveCrunchInputs(arrivals: SourceQueueWithComplete[ArrivalsDiff], manifests: SourceQueueWithComplete[VoyageManifests])

case class ForecastCrunchInputs(arrivals: SourceQueueWithComplete[ArrivalsDiff])

case class ArrivalsInputs(base: SourceQueueWithComplete[Flights], live: SourceQueueWithComplete[Flights])

case class CrunchSystem(shifts: SourceQueueWithComplete[String],
                        fixedPoints: SourceQueueWithComplete[String],
                        staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                        baseArrivals: SourceQueueWithComplete[Flights],
                        liveArrivals: SourceQueueWithComplete[Flights],
                        actualDeskStats: SourceQueueWithComplete[ActualDeskStats],
                        manifests: SourceQueueWithComplete[VoyageManifests]
                       )

object CrunchSystem {

  case class CrunchProps(system: ActorSystem, airportConfig: AirportConfig, pcpArrival: Arrival => MilliDate, historicalSplitsProvider: SplitsProvider, liveCrunchStateActor: ActorRef, forecastCrunchStateActor: ActorRef, maxDaysToCrunch: Int)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(props: CrunchProps): CrunchSystem = {
//    implicit val actorSystem: ActorSystem = props.system
//    implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()

    val baseArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "base-arrivals-actor")
    val liveArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

    val staffingGraphStage = new StaffingStage(None, props.airportConfig.minMaxDesksByTerminalQueue, props.airportConfig.slaByQueue)
    val actualDesksAndQueuesStage = new ActualDesksAndWaitTimesGraphStage()

    val simInputs: SimulationInputs = startRunnableSimulation(props.system, props.liveCrunchStateActor, staffingGraphStage, actualDesksAndQueuesStage)
    val liveCrunchInputs: LiveCrunchInputs = startLiveRunnableCrunch(props.system, simInputs.crunch, airportConfig = props.airportConfig, props.historicalSplitsProvider)
    val forecastCrunchInputs: ForecastCrunchInputs = startRunnableForecastCrunch(props.system, props.forecastCrunchStateActor, props.maxDaysToCrunch, props.airportConfig, props.historicalSplitsProvider)
    val arrivalsInputs: ArrivalsInputs = startRunnableArrivals(props.system, List(liveCrunchInputs.arrivals, forecastCrunchInputs.arrivals), baseArrivalsActor, liveArrivalsActor, props.pcpArrival, props.airportConfig)

    CrunchSystem(
      simInputs.shifts,
      simInputs.fixedPoints,
      simInputs.staffMovements,
      arrivalsInputs.base,
      arrivalsInputs.live,
      simInputs.actualDeskStats,
      liveCrunchInputs.manifests
    )
  }

  def arrivalsStage(baseArrivalsActor: ActorRef, liveArrivalsActor: ActorRef, pcpArrival: Arrival => MilliDate, airportConfig: AirportConfig) = new ArrivalsGraphStage(
    initialBaseArrivals = initialArrivals(baseArrivalsActor),
    initialLiveArrivals = initialArrivals(liveArrivalsActor),
    baseArrivalsActor = baseArrivalsActor,
    liveArrivalsActor = liveArrivalsActor,
    pcpArrivalTime = pcpArrival,
    validPortTerminals = airportConfig.terminalNames.toSet)

  def crunchStage(name: String, maxDays: Int, manifestsUsed: Boolean = true, airportConfig: AirportConfig, historicalSplitsProvider: SplitsProvider): CrunchGraphStage = new CrunchGraphStage(
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
    earliestAndLatestAffectedPcpTime = earliestAndLatestAffectedPcpTimeFromFlights(maxDays = maxDays),
    manifestsUsed = manifestsUsed)

  def startRunnableSimulation(implicit system: ActorSystem, crunchStateActor: ActorRef, staffingStage: StaffingStage, actualDesksStage: ActualDesksAndWaitTimesGraphStage): SimulationInputs = {
    val crunchSource: Source[PortState, SourceQueueWithComplete[PortState]] = Source.queue[PortState](10, OverflowStrategy.backpressure)
    val shiftsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](10, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](10, OverflowStrategy.backpressure)

    val (liveCrunchInput, shiftsInput, fixedPointsInput, staffMovementsInput, actualDesksAndQueuesInput) = RunnableSimulationGraph(
      crunchStateActor = crunchStateActor,
      crunchSource = crunchSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
      staffingStage = staffingStage,
      actualDesksStage = actualDesksStage
    ).run()(ActorMaterializer())

    SimulationInputs(liveCrunchInput, shiftsInput, fixedPointsInput, staffMovementsInput, actualDesksAndQueuesInput)
  }

  def startLiveRunnableCrunch(implicit system: ActorSystem, simulationSubscriber: SourceQueueWithComplete[PortState], airportConfig: AirportConfig, historicalSplitsProvider: SplitsProvider): LiveCrunchInputs = {
    val manifestsSource: Source[VoyageManifests, SourceQueueWithComplete[VoyageManifests]] = Source.queue[VoyageManifests](100, OverflowStrategy.backpressure)
    val liveArrivalsDiffQueueSource: Source[ArrivalsDiff, SourceQueueWithComplete[ArrivalsDiff]] = Source.queue[ArrivalsDiff](0, OverflowStrategy.backpressure)
    val (liveArrivalsCrunchInput, manifestsInput) = RunnableCrunchGraph[SourceQueueWithComplete[ArrivalsDiff], SourceQueueWithComplete[VoyageManifests]](
      arrivalsSource = liveArrivalsDiffQueueSource,
      voyageManifestsSource = manifestsSource,
      cruncher = crunchStage(name = "live", maxDays = 2, airportConfig = airportConfig, historicalSplitsProvider = historicalSplitsProvider),
      simulationQueueSubscriber = simulationSubscriber
    ).run()(ActorMaterializer())

    LiveCrunchInputs(liveArrivalsCrunchInput, manifestsInput)
  }

  def startRunnableForecastCrunch(implicit system: ActorSystem, crunchStateActor: ActorRef, maxDaysToCrunch: Int, airportConfig: AirportConfig, historicalSplitsProvider: SplitsProvider): ForecastCrunchInputs = {
    val forecastArrivalsDiffQueueSource: Source[ArrivalsDiff, SourceQueueWithComplete[ArrivalsDiff]] = Source.queue[ArrivalsDiff](0, OverflowStrategy.backpressure)
    val forecastArrivalsCrunchInput: SourceQueueWithComplete[ArrivalsDiff] = RunnableForecastCrunchGraph[SourceQueueWithComplete[ArrivalsDiff]](
      arrivalsSource = forecastArrivalsDiffQueueSource,
      cruncher = crunchStage(name = "forecast", maxDays = maxDaysToCrunch, manifestsUsed = false, airportConfig = airportConfig, historicalSplitsProvider),
      crunchSinkActor = crunchStateActor
    ).run()(ActorMaterializer())

    ForecastCrunchInputs(forecastArrivalsCrunchInput)
  }

  def startRunnableArrivals(implicit system: ActorSystem, crunchSubscribers: List[SourceQueueWithComplete[ArrivalsDiff]], baseArrivalsActor: ActorRef, liveArrivalsActor: ActorRef, pcpArrival: Arrival => MilliDate, airportConfig: AirportConfig): ArrivalsInputs = {
    val baseArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](10, OverflowStrategy.backpressure)
    val liveArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](10, OverflowStrategy.backpressure)

    val (baseArrivalsInput, liveArrivalsInput) = RunnableArrivalsGraph[SourceQueueWithComplete[Flights]](
      baseArrivals,
      liveArrivals,
      arrivalsStage(baseArrivalsActor = baseArrivalsActor, liveArrivalsActor = liveArrivalsActor, pcpArrival, airportConfig),
      crunchSubscribers
    ).run()(ActorMaterializer())

    ArrivalsInputs(baseArrivalsInput, liveArrivalsInput)
  }

  def initialArrivals(arrivalsActor: AskableActorRef): Set[Arrival] = {
    val arrivalsFuture: Future[Set[Arrival]] = arrivalsActor.ask(GetState)(new Timeout(1 minute)).map {
      case ArrivalsState(arrivals) => arrivals.values.toSet
      case _ => Set[Arrival]()
    }
    arrivalsFuture.onComplete {
      case Success(arrivals) => arrivals
      case Failure(t) =>
        log.warn(s"Failed to get an initial ArrivalsState: $t")
        Set[Arrival]()
    }
    Await.result(arrivalsFuture, 1 minute)
  }
}
