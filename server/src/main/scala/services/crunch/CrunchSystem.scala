package services.crunch

import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services._
import services.graphstages.Crunch.{earliestAndLatestAffectedPcpTimeFromFlights, getLocalLastMidnight, getLocalNextMidnight}
import services.graphstages._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
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

case class ArrivalsInputs(base: SourceQueueWithComplete[Flights], forecast: SourceQueueWithComplete[Flights], live: SourceQueueWithComplete[Flights])

case class CrunchSystem(shifts: List[SourceQueueWithComplete[String]],
                        fixedPoints: List[SourceQueueWithComplete[String]],
                        staffMovements: List[SourceQueueWithComplete[Seq[StaffMovement]]],
                        baseArrivals: List[SourceQueueWithComplete[Flights]],
                        forecastArrivals: List[SourceQueueWithComplete[Flights]],
                        liveArrivals: List[SourceQueueWithComplete[Flights]],
                        actualDeskStats: SourceQueueWithComplete[ActualDeskStats],
                        manifests: SourceQueueWithComplete[VoyageManifests]
                       )

object CrunchSystem {

  case class CrunchProps(system: ActorSystem,
                         airportConfig: AirportConfig,
                         pcpArrival: Arrival => MilliDate,
                         historicalSplitsProvider: SplitsProvider.SplitProvider,
                         liveCrunchStateActor: ActorRef,
                         forecastCrunchStateActor: ActorRef,
                         maxDaysToCrunch: Int,
                         expireAfterMillis: Long,
                         minutesToCrunch: Int,
                         warmUpMinutes: Int,
                         actors: Map[String, AskableActorRef],
                         useNationalityBasedProcessingTimes: Boolean
                        )

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(props: CrunchProps): CrunchSystem = {

    val baseArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "base-arrivals-actor")
    val forecastArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[ForecastPortArrivalsActor]), name = "forecast-arrivals-actor")
    val liveArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

    val askableLiveCrunchStateActor: AskableActorRef = props.liveCrunchStateActor
    val askableForecastCrunchStateActor: AskableActorRef = props.forecastCrunchStateActor

    val initialLiveCrunchState = initialPortState(askableLiveCrunchStateActor)
    val initialForecastCrunchState = initialPortState(askableForecastCrunchStateActor)
    val initialShifts = initialShiftsLikeState(props.actors("shifts"))
    val initialFixedPoints = initialShiftsLikeState(props.actors("fixed-points"))
    val initialStaffMovements = initialStaffMovementsState(props.actors("staff-movements"))

    def staffingStage(name: String, initialPortState: Option[PortState], crunchEnd: SDateLike => SDateLike, minutesToCrunch: Int, warmUpMinutes: Int, eGateBankSize: Int) = new StaffingStage(
      name = name,
      initialOptionalPortState = initialPortState,
      initialShifts = initialShifts,
      initialFixedPoints = initialFixedPoints,
      initialMovements = initialStaffMovements,
      minMaxDesks = props.airportConfig.minMaxDesksByTerminalQueue,
      slaByQueue = props.airportConfig.slaByQueue,
      minutesToCrunch = minutesToCrunch,
      warmUpMinutes = warmUpMinutes,
      crunchEnd = crunchEnd,
      now = () => SDate.now(),
      expireAfterMillis = props.expireAfterMillis,
      eGateBankSize = eGateBankSize)

    val actualDesksAndQueuesStage = new ActualDesksAndWaitTimesGraphStage()

    val liveStaffingStage = staffingStage("live", initialLiveCrunchState, (minute: SDateLike) => getLocalNextMidnight(minute), props.minutesToCrunch, props.warmUpMinutes, props.airportConfig.eGateBankSize)

    val forecastStaffingStage = staffingStage("forecast", initialForecastCrunchState, (minute: SDateLike) => getLocalNextMidnight(minute), props.minutesToCrunch, props.warmUpMinutes, props.airportConfig.eGateBankSize)

    val baseArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](10, OverflowStrategy.dropHead)
    val forecastArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](10, OverflowStrategy.dropHead)
    val liveArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](10, OverflowStrategy.dropHead)
    val shiftsSourceLive: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.dropHead)
    val fixedPointsSourceLive: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](10, OverflowStrategy.dropHead)
    val staffMovementsSourceLive: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](10, OverflowStrategy.dropHead)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](10, OverflowStrategy.dropHead)
    val manifestsSource: Source[VoyageManifests, SourceQueueWithComplete[VoyageManifests]] = Source.queue[VoyageManifests](100, OverflowStrategy.dropHead)
    val liveCrunchStage = crunchStage(name = "live", portCode = props.airportConfig.portCode, maxDays = 2, manifestsUsed = true, airportConfig = props.airportConfig,
      historicalSplitsProvider = props.historicalSplitsProvider, expireAfterMillis = props.expireAfterMillis,
      minutesToCrunch = props.minutesToCrunch, warmUpMinutes = props.warmUpMinutes, useNationalityBasedProcessingTimes = props.useNationalityBasedProcessingTimes)
    val forecastCrunchStage = crunchStage(name = "forecast", portCode = props.airportConfig.portCode, maxDays = 2, manifestsUsed = false, airportConfig = props.airportConfig,
      historicalSplitsProvider = props.historicalSplitsProvider, expireAfterMillis = props.expireAfterMillis,
      minutesToCrunch = props.minutesToCrunch, warmUpMinutes = props.warmUpMinutes, useNationalityBasedProcessingTimes = props.useNationalityBasedProcessingTimes)
    val arrivalsStage_ = arrivalsStage(baseArrivalsActor = baseArrivalsActor, forecastArrivalsActor = forecastArrivalsActor, liveArrivalsActor = liveArrivalsActor, props.pcpArrival, props.airportConfig, props.expireAfterMillis)

    val runnableCrunch = RunnableCrunchLive(
      baseArrivals, forecastArrivals, liveArrivals, manifestsSource, shiftsSourceLive, fixedPointsSourceLive, staffMovementsSourceLive,
      actualDesksAndQueuesSource, arrivalsStage_, liveCrunchStage, liveStaffingStage, actualDesksAndQueuesStage, props.liveCrunchStateActor)

    val runnableCrunchForecast = RunnableCrunchForecast(
      baseArrivals, forecastArrivals, liveArrivals, shiftsSourceLive, fixedPointsSourceLive, staffMovementsSourceLive,
      arrivalsStage_, forecastCrunchStage, forecastStaffingStage, props.forecastCrunchStateActor)

    implicit val actorSystem: ActorSystem = props.system
    val (baseInputL, forecastInputL, liveInputL, manifestsInputL, shiftsInputL, fixedPointsInputL, movementsInputL, actualDesksInputL) = runnableCrunch.run()(ActorMaterializer())
    val (baseInputF, forecastInputF, liveInputF, shiftsInputF, fixedPointsInputF, movementsInputF) = runnableCrunchForecast.run()(ActorMaterializer())

    CrunchSystem(
      shifts = List(shiftsInputL, shiftsInputF),
      fixedPoints = List(fixedPointsInputL, fixedPointsInputF),
      staffMovements = List(movementsInputL, movementsInputF),
      baseArrivals = List(baseInputL, baseInputF),
      forecastArrivals = List(forecastInputL, forecastInputF),
      liveArrivals = List(liveInputL, liveInputF),
      actualDeskStats = actualDesksInputL,
      manifests = manifestsInputL
    )
  }

  def initialPortState(askableCrunchStateActor: AskableActorRef): Option[PortState] = {
    Await.result(askableCrunchStateActor.ask(GetState)(new Timeout(5 minutes)).map {
      case Some(ps: PortState) =>
        log.info(s"Got an initial port state from ${askableCrunchStateActor.toString} with ${ps.staffMinutes.size} staff minutes, ${ps.crunchMinutes.size} crunch minutes, and ${ps.flights.size} flights")
        Option(ps)
      case _ =>
        log.info(s"Got no initial port state from ${askableCrunchStateActor.toString}")
        None
    }, 5 minutes)
  }

  def initialShiftsLikeState(askableShiftsLikeActor: AskableActorRef): String = {
    Await.result(askableShiftsLikeActor.ask(GetState)(new Timeout(5 minutes)).map {
      case shifts: String if shifts.nonEmpty =>
        log.info(s"Got initial state from ${askableShiftsLikeActor.toString}")
        shifts
      case _ =>
        log.info(s"Got no initial state from ${askableShiftsLikeActor.toString}")
        ""
    }, 5 minutes)
  }

  def initialStaffMovementsState(askableStaffMovementsActor: AskableActorRef): Seq[StaffMovement] = {
    Await.result(askableStaffMovementsActor.ask(GetState)(new Timeout(5 minutes)).map {
      case StaffMovements(mms) if mms.nonEmpty =>
        log.info(s"Got initial state from ${askableStaffMovementsActor.toString}")
        mms
      case _ =>
        log.info(s"Got no initial state from ${askableStaffMovementsActor.toString}")
        Seq()
    }, 5 minutes)
  }

  def arrivalsStage(baseArrivalsActor: ActorRef, forecastArrivalsActor: ActorRef, liveArrivalsActor: ActorRef, pcpArrival: Arrival => MilliDate, airportConfig: AirportConfig, expireAfterMillis: Long) = new ArrivalsGraphStage(
    initialBaseArrivals = initialArrivals(baseArrivalsActor),
    initialForecastArrivals = initialArrivals(forecastArrivalsActor),
    initialLiveArrivals = initialArrivals(liveArrivalsActor),
    baseArrivalsActor = baseArrivalsActor,
    forecastArrivalsActor = forecastArrivalsActor,
    liveArrivalsActor = liveArrivalsActor,
    pcpArrivalTime = pcpArrival,
    validPortTerminals = airportConfig.terminalNames.toSet,
    expireAfterMillis = expireAfterMillis,
    now = () => SDate.now())

  def crunchStage(name: String,
                  portCode: String,
                  maxDays: Int,
                  manifestsUsed: Boolean = true,
                  airportConfig: AirportConfig,
                  historicalSplitsProvider: SplitsProvider.SplitProvider,
                  expireAfterMillis: Long,
                  minutesToCrunch: Int,
                  warmUpMinutes: Int, useNationalityBasedProcessingTimes: Boolean): CrunchGraphStage = new CrunchGraphStage(
    name = name,
    optionalInitialFlights = None,
    airportConfig = airportConfig,
    natProcTimes = AirportConfigs.nationalityProcessingTimes,
    groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
    splitsCalculator = SplitsCalculator(airportConfig.portCode, historicalSplitsProvider, airportConfig.defaultPaxSplits.splits.toSet),
    crunchStartFromFirstPcp = getLocalLastMidnight,
    crunchEndFromLastPcp = (maxPcpTime: SDateLike) => getLocalNextMidnight(maxPcpTime),
    expireAfterMillis = expireAfterMillis,
    now = () => SDate.now(),
    maxDaysToCrunch = maxDays,
    earliestAndLatestAffectedPcpTime = earliestAndLatestAffectedPcpTimeFromFlights(maxDays = maxDays),
    manifestsUsed = manifestsUsed,
    minutesToCrunch = minutesToCrunch,
    warmUpMinutes = warmUpMinutes,
    useNationalityBasedProcessingTimes = useNationalityBasedProcessingTimes)

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
