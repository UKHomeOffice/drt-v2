package services.crunch

import actors.{GetState, StaffMovements, VoyageManifestState}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.AskableActorRef
import akka.stream._
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.util.Timeout
import drt.chroma.ArrivalsDiffingStage
import drt.shared.CrunchApi.{CrunchMinutes, PortState, StaffMinutes}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{SDateLike, _}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services._
import services.graphstages.Crunch._
import services.graphstages._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


case class CrunchSystem[FR, MS](shifts: SourceQueueWithComplete[ShiftAssignments],
                                fixedPoints: SourceQueueWithComplete[FixedPointAssignments],
                                staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                                baseArrivalsResponse: FR,
                                forecastArrivalsResponse: FR,
                                liveArrivalsResponse: FR,
                                manifestsResponse: MS,
                                actualDeskStats: SourceQueueWithComplete[ActualDeskStats],
                                killSwitches: List[KillSwitch]
                               )

case class CrunchProps[FR, MS](logLabel: String = "",
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
                               manifestsSource: Source[ManifestsFeedResponse, MS],
                               voyageManifestsActor: ActorRef,
                               cruncher: TryCrunch,
                               simulator: Simulator,
                               initialPortState: Option[PortState] = None,
                               initialBaseArrivals: Set[Arrival] = Set(),
                               initialFcstArrivals: Set[Arrival] = Set(),
                               initialLiveArrivals: Set[Arrival] = Set(),
                               initialMergeArrivals: Map[Int, Arrival] = Map(),
                               initialManifestsState: Option[VoyageManifestState],
                               arrivalsBaseSource: Source[ArrivalsFeedResponse, FR],
                               arrivalsFcstSource: Source[ArrivalsFeedResponse, FR],
                               arrivalsLiveSource: Source[ArrivalsFeedResponse, FR],
                               recrunchOnStart: Boolean = false)

object CrunchSystem {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def crunchStartWithOffset(offsetMinutes: Int)(minuteInQuestion: SDateLike): SDateLike = {
    val adjustedMinute = minuteInQuestion.addMinutes(-offsetMinutes)
    Crunch.getLocalLastMidnight(MilliDate(adjustedMinute.millisSinceEpoch)).addMinutes(offsetMinutes)
  }

  def apply[FR, MS](props: CrunchProps[FR, MS])
                   (implicit system: ActorSystem, materializer: Materializer): CrunchSystem[FR, MS] = {

    val initialShifts = initialShiftsState(props.actors("shifts"))
    val initialFixedPoints = initialFixedPointsState(props.actors("fixed-points"))
    val initialStaffMovements = initialStaffMovementsState(props.actors("staff-movements"))

    val manifests = props.manifestsSource
    val shiftsSource: Source[ShiftAssignments, SourceQueueWithComplete[ShiftAssignments]] = Source.queue[ShiftAssignments](1, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[FixedPointAssignments, SourceQueueWithComplete[FixedPointAssignments]] = Source.queue[FixedPointAssignments](1, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](1, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](1, OverflowStrategy.backpressure)

    val splitsCalculator = SplitsCalculator(props.airportConfig.feedPortCode, props.historicalSplitsProvider, props.airportConfig.defaultPaxSplits.splits.toSet)
    val groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight) _
    val crunchStartDateProvider: SDateLike => SDateLike = crunchStartWithOffset(props.airportConfig.crunchOffsetMinutes)

    val maybeStaffMinutes = initialStaffMinutesFromPortState(props.initialPortState)
    val maybeCrunchMinutes = initialCrunchMinutesFromPortState(props.initialPortState)

    val initialFlightsWithSplits = initialFlightsFromPortState(props.initialPortState)

    val arrivalsStage = new ArrivalsGraphStage(
      name = props.logLabel,
      initialBaseArrivals = if (props.recrunchOnStart) Set() else props.initialBaseArrivals,
      initialForecastArrivals = if (props.recrunchOnStart) Set() else props.initialFcstArrivals,
      initialLiveArrivals =  if (props.recrunchOnStart) Set() else props.initialLiveArrivals,
      initialMergedArrivals =  if (props.recrunchOnStart) Map() else {
        initialFlightsWithSplits.map(_.flights.map(fws => (fws.apiFlight.uniqueId, fws.apiFlight)).toMap).getOrElse(Map())
      },
      pcpArrivalTime = props.pcpArrival,
      validPortTerminals = props.airportConfig.terminalNames.toSet,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now)

    val fcstArrivalsDiffingStage = new ArrivalsDiffingStage(props.initialFcstArrivals.toSeq)
    val liveArrivalsDiffingStage = new ArrivalsDiffingStage(props.initialLiveArrivals.toSeq)

    val arrivalSplitsGraphStage = new ArrivalSplitsGraphStage(
      name = props.logLabel,
      optionalInitialFlights = initialFlightsWithSplits,
      optionalInitialManifests = props.initialManifestsState.map(_.manifests),
      splitsCalculator = splitsCalculator,
      groupFlightsByCodeShares = groupFlightsByCodeShares,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      maxDaysToCrunch = props.maxDaysToCrunch)

    val splitsPredictorStage = props.splitsPredictorStage

    val staffGraphStage = new StaffGraphStage(
      name = props.logLabel,
      initialShifts = initialShifts,
      initialFixedPoints = initialFixedPoints,
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
      optionalInitialFlightsWithSplits = initialFlightsWithSplits,
      airportConfig = props.airportConfig,
      natProcTimes = props.airportConfig.nationalityBasedProcTimes,
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

    val crunchSystem = RunnableCrunch(
      props.arrivalsBaseSource, props.arrivalsFcstSource, props.arrivalsLiveSource, manifests, shiftsSource, fixedPointsSource, staffMovementsSource, actualDesksAndQueuesSource,
      arrivalsStage, arrivalSplitsGraphStage, splitsPredictorStage, workloadGraphStage, loadBatcher, crunchLoadGraphStage, staffGraphStage, staffBatcher, simulationGraphStage, portStateGraphStage, fcstArrivalsDiffingStage, liveArrivalsDiffingStage,
      props.actors("base-arrivals").actorRef, props.actors("forecast-arrivals").actorRef, props.actors("live-arrivals").actorRef,
      props.voyageManifestsActor,
      props.liveCrunchStateActor, props.forecastCrunchStateActor,
      crunchStartDateProvider, props.now
    )

    val (baseIn, fcstIn, liveIn, manifestsIn, shiftsIn, fixedPointsIn, movementsIn, actDesksIn, arrivalsKillSwitch, manifestsKillSwitch) = crunchSystem.run

    CrunchSystem(
      shifts = shiftsIn,
      fixedPoints = fixedPointsIn,
      staffMovements = movementsIn,
      baseArrivalsResponse = baseIn,
      forecastArrivalsResponse = fcstIn,
      liveArrivalsResponse = liveIn,
      manifestsResponse = manifestsIn,
      actualDeskStats = actDesksIn,
      List(arrivalsKillSwitch, manifestsKillSwitch)
    )
  }

  def arrivalsDiffingStage(initialArrivals: Seq[Arrival]) = new ArrivalsDiffingStage(initialArrivals)

  def initialStaffMinutesFromPortState(initialPortState: Option[PortState]): Option[StaffMinutes] = initialPortState.map(
    ps => StaffMinutes(ps.staffMinutes))

  def initialCrunchMinutesFromPortState(initialPortState: Option[PortState]): Option[CrunchMinutes] = initialPortState.map(
    ps => CrunchMinutes(ps.crunchMinutes.values.toSet))

  def initialLoadsFromPortState(initialPortState: Option[PortState]): Option[Loads] = initialPortState.map(ps => Loads(ps.crunchMinutes.values.toSeq))

  def initialFlightsFromPortState(initialPortState: Option[PortState]): Option[FlightsWithSplits] = initialPortState.map(
    ps => FlightsWithSplits(ps.flights.values.toSeq, Set()))

  def initialFixedPointsState(askableFixedPointsActor: AskableActorRef): FixedPointAssignments = {
    Await.result(askableFixedPointsActor.ask(GetState)(new Timeout(5 minutes)).map {
      case fixedPoints: FixedPointAssignments =>
        log.info(s"Got initial state from ${askableFixedPointsActor.toString}")
        fixedPoints
      case _ =>
        log.info(s"Got unexpected GetState response from ${askableFixedPointsActor.toString}")
        FixedPointAssignments.empty
    }, 5 minutes)
  }

  def initialShiftsState(askableShiftsActor: AskableActorRef): ShiftAssignments = {
    Await.result(askableShiftsActor.ask(GetState)(new Timeout(5 minutes)).map {
      case shifts: ShiftAssignments =>
        log.info(s"Got initial state from ${askableShiftsActor.toString}")
        shifts
      case _ =>
        log.info(s"Got unexpected GetState response from ${askableShiftsActor.toString}")
        ShiftAssignments.empty
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
