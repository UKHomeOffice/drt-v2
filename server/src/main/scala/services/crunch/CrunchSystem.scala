package services.crunch

import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
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


case class CrunchSystem(shifts: SourceQueueWithComplete[String],
                        fixedPoints: SourceQueueWithComplete[String],
                        staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                        baseArrivals: SourceQueueWithComplete[Flights],
                        forecastArrivals: SourceQueueWithComplete[Flights],
                        liveArrivals: SourceQueueWithComplete[Flights],
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
                         useNationalityBasedProcessingTimes: Boolean,
                         crunchStartDateProvider: (SDateLike) => SDateLike = getLocalLastMidnight,
                         crunchEndDateProvider: (SDateLike) => SDateLike = (maxPcpTime: SDateLike) => getLocalNextMidnight(maxPcpTime),
                         calcPcpTimeWindow: Int => (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)] = (maxDays: Int) => earliestAndLatestAffectedPcpTimeFromFlights(maxDays = maxDays),
                         now: () => SDateLike = () => SDate.now(),
                         initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                         splitsPredictorStage: SplitsPredictorBase,
                         waitForManifests: Boolean = true
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
      now = props.now,
      expireAfterMillis = props.expireAfterMillis,
      eGateBankSize = eGateBankSize)

    val actualDesksStage = new ActualDesksAndWaitTimesGraphStage()

    val liveStaffingStage = staffingStage("live", initialLiveCrunchState, (minute: SDateLike) => getLocalNextMidnight(minute), props.minutesToCrunch, props.warmUpMinutes, props.airportConfig.eGateBankSize)

    val forecastStaffingStage = staffingStage("forecast", initialForecastCrunchState, (minute: SDateLike) => getLocalNextMidnight(minute), props.minutesToCrunch, props.warmUpMinutes, props.airportConfig.eGateBankSize)

    val baseArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](1, OverflowStrategy.backpressure)
    val forecastArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](1, OverflowStrategy.backpressure)
    val liveArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](1, OverflowStrategy.backpressure)
    val shiftsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](1, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](1, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](1, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](1, OverflowStrategy.backpressure)
    val manifestsSource: Source[VoyageManifests, SourceQueueWithComplete[VoyageManifests]] = Source.queue[VoyageManifests](1, OverflowStrategy.backpressure)

    val maxLiveDaysToCrunch = 2

    val liveCrunchStage = new CrunchGraphStage(
      name = "live",
      optionalInitialFlights = props.initialFlightsWithSplits,
      airportConfig = props.airportConfig,
      natProcTimes = AirportConfigs.nationalityProcessingTimes,
      groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
      splitsCalculator = SplitsCalculator(props.airportConfig.portCode, props.historicalSplitsProvider, props.airportConfig.defaultPaxSplits.splits.toSet),
      crunchStartFromFirstPcp = props.crunchStartDateProvider,
      crunchEndFromLastPcp = props.crunchEndDateProvider,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      maxDaysToCrunch = maxLiveDaysToCrunch,
      earliestAndLatestAffectedPcpTime = props.calcPcpTimeWindow(maxLiveDaysToCrunch),
      waitForManifests = props.waitForManifests,
      minutesToCrunch = props.minutesToCrunch,
      warmUpMinutes = props.warmUpMinutes,
      useNationalityBasedProcessingTimes = props.useNationalityBasedProcessingTimes)

    val forecastCrunchStage = new CrunchGraphStage(
      name = "forecast",
      optionalInitialFlights = None,
      airportConfig = props.airportConfig,
      natProcTimes = AirportConfigs.nationalityProcessingTimes,
      groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
      splitsCalculator = SplitsCalculator(props.airportConfig.portCode, props.historicalSplitsProvider, props.airportConfig.defaultPaxSplits.splits.toSet),
      crunchStartFromFirstPcp = props.crunchStartDateProvider,
      crunchEndFromLastPcp = props.crunchEndDateProvider,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      maxDaysToCrunch = props.maxDaysToCrunch,
      earliestAndLatestAffectedPcpTime = props.calcPcpTimeWindow(props.maxDaysToCrunch),
      waitForManifests = props.waitForManifests,
      minutesToCrunch = props.minutesToCrunch,
      warmUpMinutes = props.warmUpMinutes,
      useNationalityBasedProcessingTimes = props.useNationalityBasedProcessingTimes)

    val arrivalsStageLive = arrivalsStage(
      name = "live",
      baseArrivalsActor = baseArrivalsActor,
      forecastArrivalsActor = forecastArrivalsActor,
      liveArrivalsActor = liveArrivalsActor,
      props.pcpArrival,
      props.airportConfig,
      props.expireAfterMillis,
      now = props.now)
    val arrivalsStageForecast = arrivalsStage(
      name = "forecast",
      baseArrivalsActor = baseArrivalsActor,
      forecastArrivalsActor = forecastArrivalsActor,
      liveArrivalsActor = liveArrivalsActor,
      props.pcpArrival,
      props.airportConfig,
      props.expireAfterMillis,
      now = props.now)

    val runnableCrunch = RunnableCrunch(
      baseArrivals, forecastArrivals, liveArrivals,
      baseArrivalsActor, forecastArrivalsActor, liveArrivalsActor,
      manifestsSource, props.splitsPredictorStage, shiftsSource, fixedPointsSource, staffMovementsSource,
      actualDesksAndQueuesSource, arrivalsStageLive, arrivalsStageForecast, actualDesksStage,
      liveCrunchStage, liveStaffingStage, props.liveCrunchStateActor,
      forecastCrunchStage, forecastStaffingStage, props.forecastCrunchStateActor)

    implicit val actorSystem: ActorSystem = props.system
    val (baseInput, forecastInput, liveInput, manifestsInput, shiftsInput, fixedPointsInput, movementsInput, actualDesksInput) = runnableCrunch.run()(ActorMaterializer())

    CrunchSystem(
      shifts = shiftsInput,
      fixedPoints = fixedPointsInput,
      staffMovements = movementsInput,
      baseArrivals = baseInput,
      forecastArrivals = forecastInput,
      liveArrivals = liveInput,
      actualDeskStats = actualDesksInput,
      manifests = manifestsInput
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

  def arrivalsStage(name: String,
                    baseArrivalsActor: ActorRef,
                    forecastArrivalsActor: ActorRef,
                    liveArrivalsActor: ActorRef,
                    pcpArrival: Arrival => MilliDate,
                    airportConfig: AirportConfig,
                    expireAfterMillis: Long,
                    now: () => SDateLike) = new ArrivalsGraphStage(
    name = name,
    initialBaseArrivals = initialArrivals(baseArrivalsActor),
    initialForecastArrivals = initialArrivals(forecastArrivalsActor),
    initialLiveArrivals = initialArrivals(liveArrivalsActor),
    pcpArrivalTime = pcpArrival,
    validPortTerminals = airportConfig.terminalNames.toSet,
    expireAfterMillis = expireAfterMillis,
    now = now)

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
