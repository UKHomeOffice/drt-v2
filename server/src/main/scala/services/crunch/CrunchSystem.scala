package services.crunch

import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{RunnableGraph, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, KillSwitch, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinutes, PortState, StaffMinutes}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.{SDateLike, _}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import server.feeds.FeedResponse
import services._
import services.graphstages.Crunch._
import services.graphstages._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


case class CrunchSystem[MS, OAL, FR](shifts: SourceQueueWithComplete[String],
                                     fixedPoints: SourceQueueWithComplete[String],
                                     staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                                     baseArrivals: OAL,
                                     forecastArrivalsResponse: FR,
                                     liveArrivalsResponse: FR,
                                     manifests: MS,
                                     actualDeskStats: SourceQueueWithComplete[ActualDeskStats],
                                     killSwitches: List[KillSwitch]
                                    )

case class CrunchProps[MS, OAL, FR](logLabel: String = "",
                                    system: ActorSystem,
                                    airportConfig: AirportConfig,
                                    pcpArrival: Arrival => MilliDate,
                                    historicalSplitsProvider: SplitsProvider.SplitProvider,
                                    liveCrunchStateActor: ActorRef,
                                    forecastCrunchStateActor: ActorRef,
                                    maxDaysToCrunch: Int,
                                    expireAfterMillis: Long,
                                    minutesToCrunch: Int = 1440,
                                    crunchOffsetMillis: Long = 0,
                                    actors: Map[String, AskableActorRef],
                                    useNationalityBasedProcessingTimes: Boolean,
                                    now: () => SDateLike = () => SDate.now(),
                                    initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                                    splitsPredictorStage: SplitsPredictorBase,
                                    manifestsSource: Source[DqManifests, MS],
                                    voyageManifestsActor: ActorRef,
                                    cruncher: TryCrunch,
                                    simulator: Simulator,
                                    initialPortState: Option[PortState] = None,
                                    initialBaseArrivals: Set[Arrival] = Set(),
                                    initialFcstArrivals: Set[Arrival] = Set(),
                                    initialLiveArrivals: Set[Arrival] = Set(),
                                    arrivalsBaseSource: Source[Option[Flights], OAL],
                                    arrivalsFcstSource: Source[FeedResponse, FR],
                                    arrivalsLiveSource: Source[FeedResponse, FR],
                                    recrunchOnStart: Boolean = false)

object CrunchSystem {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def crunchStartWithOffset(offsetMinutes: Int)(minuteInQuestion: SDateLike): SDateLike = {
    val adjustedMinute = minuteInQuestion.addMinutes(-offsetMinutes)
    Crunch.getLocalLastMidnight(adjustedMinute).addMinutes(offsetMinutes)
  }

  def apply[MS, OAL, FR](props: CrunchProps[MS, OAL, FR]): CrunchSystem[MS, OAL, FR] = {

    val initialShifts = initialShiftsLikeState(props.actors("shifts"))
    val initialFixedPoints = initialShiftsLikeState(props.actors("fixed-points"))
    val initialStaffMovements = initialStaffMovementsState(props.actors("staff-movements"))

    val manifests = props.manifestsSource
    val shiftsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](1, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](1, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](1, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](1, OverflowStrategy.backpressure)

    val splitsCalculator = SplitsCalculator(props.airportConfig.portCode, props.historicalSplitsProvider, props.airportConfig.defaultPaxSplits.splits.toSet)
    val groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight) _
    val crunchStartDateProvider: (SDateLike) => SDateLike = crunchStartWithOffset(props.airportConfig.crunchOffsetMinutes)

    val maybeStaffMinutes = initialStaffMinutesFromPortState(props.initialPortState)
    val maybeCrunchMinutes = initialCrunchMinutesFromPortState(props.initialPortState)

    val arrivalsStage = new ArrivalsGraphStage(
      name = props.logLabel,
      initialBaseArrivals = props.initialBaseArrivals,
      initialForecastArrivals = props.initialFcstArrivals,
      initialLiveArrivals = props.initialLiveArrivals,
      pcpArrivalTime = props.pcpArrival,
      validPortTerminals = props.airportConfig.terminalNames.toSet,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now)

    val arrivalSplitsGraphStage = new ArrivalSplitsGraphStage(
      name = props.logLabel,
      optionalInitialFlights = initialFlightsFromPortState(props.initialPortState),
      splitsCalculator = splitsCalculator,
      groupFlightsByCodeShares = groupFlightsByCodeShares,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      maxDaysToCrunch = props.maxDaysToCrunch)

    val splitsPredictorStage = props.splitsPredictorStage

    val staffGraphStage = new StaffGraphStage(
      name = props.logLabel,
      optionalInitialShifts = Option(initialShifts),
      optionalInitialFixedPoints = Option(initialFixedPoints),
      optionalInitialMovements = Option(initialStaffMovements),
      now = props.now,
      expireAfterMillis = props.expireAfterMillis,
      airportConfig = props.airportConfig,
      numberOfDays = props.maxDaysToCrunch)

    val staffBatcher = new StaffBatchUpdateGraphStage(props.now, props.expireAfterMillis, props.airportConfig.crunchOffsetMinutes)
    val loadBatcher = new BatchLoadsByCrunchPeriodGraphStage(props.now, props.expireAfterMillis, crunchStartDateProvider)

    val workloadGraphStage = new WorkloadGraphStage(
      name = props.logLabel,
      optionalInitialLoads = if (props.recrunchOnStart) None else initialLoadsFromPortState(props.initialPortState),
      optionalInitialFlightsWithSplits = initialFlightsFromPortState(props.initialPortState),
      airportConfig = props.airportConfig,
      natProcTimes = AirportConfigs.nationalityProcessingTimes,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      useNationalityBasedProcessingTimes = props.useNationalityBasedProcessingTimes)

    val crunchLoadGraphStage = new CrunchLoadGraphStage(
      name = props.logLabel,
      optionalInitialCrunchMinutes = maybeCrunchMinutes,
      airportConfig = props.airportConfig,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      crunch = props.cruncher,
      crunchPeriodStartMillis = crunchStartDateProvider,
      minutesToCrunch = props.minutesToCrunch)

    val simulationGraphStage = new SimulationGraphStage(
      name = props.logLabel,
      optionalInitialCrunchMinutes = maybeCrunchMinutes,
      optionalInitialStaffMinutes = maybeStaffMinutes,
      airportConfig = props.airportConfig,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      simulate = props.simulator,
      crunchPeriodStartMillis = crunchStartDateProvider,
      minutesToCrunch = props.minutesToCrunch)

    val portStateGraphStage = new PortStateGraphStage(
      name = props.logLabel,
      optionalInitialPortState = props.initialPortState,
      airportConfig = props.airportConfig,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now)

    val crunchSystem: RunnableGraph[(OAL, AL, AL, MS, SourceQueueWithComplete[String], SourceQueueWithComplete[String], SourceQueueWithComplete[Seq[StaffMovement]], SourceQueueWithComplete[ActualDeskStats], UniqueKillSwitch, UniqueKillSwitch)] = RunnableCrunch(
      props.arrivalsBaseSource, props.arrivalsFcstSource, props.arrivalsLiveSource, manifests, shiftsSource, fixedPointsSource, staffMovementsSource, actualDesksAndQueuesSource,
      arrivalsStage, arrivalSplitsGraphStage, splitsPredictorStage, workloadGraphStage, loadBatcher, crunchLoadGraphStage, staffGraphStage, staffBatcher, simulationGraphStage, portStateGraphStage,
      props.actors("base-arrivals").actorRef, props.actors("forecast-arrivals").actorRef, props.actors("live-arrivals").actorRef,
      props.voyageManifestsActor,
      props.liveCrunchStateActor, props.forecastCrunchStateActor,
      crunchStartDateProvider, props.now
    )

    implicit val actorSystem: ActorSystem = props.system
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    val (baseIn, fcstIn, liveIn, manifestsIn, shiftsIn, fixedPointsIn, movementsIn, actDesksIn, arrivalsKillSwitch, manifestsKillSwitch) = crunchSystem.run

    CrunchSystem(
      shifts = shiftsIn,
      fixedPoints = fixedPointsIn,
      staffMovements = movementsIn,
      baseArrivals = baseIn,
      forecastArrivalsResponse = fcstIn,
      liveArrivalsResponse = liveIn,
      manifests = manifestsIn,
      actualDeskStats = actDesksIn,
      List(arrivalsKillSwitch, manifestsKillSwitch)
    )
  }

  def initialStaffMinutesFromPortState(initialPortState: Option[PortState]): Option[StaffMinutes] = initialPortState.map(ps => StaffMinutes(ps.staffMinutes))

  def initialCrunchMinutesFromPortState(initialPortState: Option[PortState]): Option[CrunchMinutes] = initialPortState.map(ps => CrunchMinutes(ps.crunchMinutes.values.toSet))

  def initialLoadsFromPortState(initialPortState: Option[PortState]): Option[Loads] = initialPortState.map(ps => Loads(ps.crunchMinutes.values.toSeq))

  def initialFlightsFromPortState(initialPortState: Option[PortState]): Option[FlightsWithSplits] = initialPortState.map(ps => FlightsWithSplits(ps.flights.values.toSeq))

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
}
