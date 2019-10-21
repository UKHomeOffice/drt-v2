package services.crunch

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.stream._
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import drt.chroma.ArrivalsDiffingStage
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{SDateLike, _}
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import queueus._
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services._
import services.graphstages.Crunch._
import services.graphstages._

import scala.collection.mutable


case class CrunchSystem[FR](shifts: SourceQueueWithComplete[ShiftAssignments],
                            fixedPoints: SourceQueueWithComplete[FixedPointAssignments],
                            staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                            forecastBaseArrivalsResponse: FR,
                            forecastArrivalsResponse: FR,
                            liveBaseArrivalsResponse: FR,
                            liveArrivalsResponse: FR,
                            manifestsLiveResponse: SourceQueueWithComplete[ManifestsFeedResponse],
                            actualDeskStats: SourceQueueWithComplete[ActualDeskStats],
                            killSwitches: List[UniqueKillSwitch]
                           )

case class CrunchProps[FR](logLabel: String = "",
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
                           useLegacyManifests: Boolean = false,
                           now: () => SDateLike = () => SDate.now(),
                           initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                           b5JStartDate: SDateLike,
                           manifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]],
                           manifestResponsesSource: Source[List[BestAvailableManifest], NotUsed],
                           voyageManifestsActor: ActorRef,
                           manifestRequestsSink: Sink[List[Arrival], NotUsed],
                           cruncher: TryCrunch,
                           simulator: Simulator,
                           initialPortState: Option[PortState] = None,
                           initialForecastBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap[UniqueArrival, Arrival](),
                           initialForecastArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap[UniqueArrival, Arrival](),
                           initialLiveBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap[UniqueArrival, Arrival](),
                           initialLiveArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap[UniqueArrival, Arrival](),
                           arrivalsForecastBaseSource: Source[ArrivalsFeedResponse, FR],
                           arrivalsForecastSource: Source[ArrivalsFeedResponse, FR],
                           arrivalsLiveBaseSource: Source[ArrivalsFeedResponse, FR],
                           arrivalsLiveSource: Source[ArrivalsFeedResponse, FR],
                           initialShifts: ShiftAssignments = ShiftAssignments(Seq()),
                           initialFixedPoints: FixedPointAssignments = FixedPointAssignments(Seq()),
                           initialStaffMovements: Seq[StaffMovement] = Seq(),
                           recrunchOnStart: Boolean = false,
                           refreshArrivalsOnStart: Boolean = false,
                           checkRequiredStaffUpdatesOnStartup: Boolean)

object CrunchSystem {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def crunchStartWithOffset(offsetMinutes: Int)(minuteInQuestion: SDateLike): SDateLike = {
    val adjustedMinute = minuteInQuestion.addMinutes(-offsetMinutes)
    Crunch.getLocalLastMidnight(MilliDate(adjustedMinute.millisSinceEpoch)).addMinutes(offsetMinutes)
  }

  def apply[FR](props: CrunchProps[FR])
               (implicit materializer: Materializer): CrunchSystem[FR] = {

    val shiftsSource: Source[ShiftAssignments, SourceQueueWithComplete[ShiftAssignments]] = Source.queue[ShiftAssignments](10, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[FixedPointAssignments, SourceQueueWithComplete[FixedPointAssignments]] = Source.queue[FixedPointAssignments](10, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](10, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](10, OverflowStrategy.backpressure)

    val groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight) _
    val crunchStartDateProvider: SDateLike => SDateLike = crunchStartWithOffset(props.airportConfig.crunchOffsetMinutes)

    val maybeStaffMinutes = initialStaffMinutesFromPortState(props.initialPortState)
    val maybeCrunchMinutes = initialCrunchMinutesFromPortState(props.initialPortState)

    val initialFlightsWithSplits = initialFlightsFromPortState(props.initialPortState)

    val forecastMaxMillis: () => MillisSinceEpoch = () => props.now().addDays(props.maxDaysToCrunch).millisSinceEpoch

    val arrivalsStage = new ArrivalsGraphStage(
      name = props.logLabel,
      initialForecastBaseArrivals = if (props.refreshArrivalsOnStart) mutable.SortedMap[UniqueArrival, Arrival]() else props.initialForecastBaseArrivals,
      initialForecastArrivals = if (props.refreshArrivalsOnStart) mutable.SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals,
      initialLiveBaseArrivals = if (props.refreshArrivalsOnStart) mutable.SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals,
      initialLiveArrivals = props.initialLiveArrivals,
      initialMergedArrivals = if (props.refreshArrivalsOnStart) mutable.SortedMap[UniqueArrival, Arrival]() else mutable.SortedMap[UniqueArrival, Arrival]() ++ initialFlightsWithSplits.map(_.flightsToUpdate.map(fws => (fws.apiFlight.unique, fws.apiFlight))).getOrElse(List()),
      pcpArrivalTime = props.pcpArrival,
      validPortTerminals = props.airportConfig.terminalNames.toSet,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now)

    val forecastArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) mutable.SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals, forecastMaxMillis)
    val liveBaseArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) mutable.SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals, forecastMaxMillis)
    val liveArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) mutable.SortedMap[UniqueArrival, Arrival]() else props.initialLiveArrivals, forecastMaxMillis)

    log.info(s"Using B5JPlus Start Date of ${props.b5JStartDate.toISOString()}")

    val ptqa = if (props.airportConfig.portCode == "LHR")
      PaxTypeQueueAllocation(
        B5JPlusWithTransitTypeAllocator(props.b5JStartDate),
        TerminalQueueAllocatorWithFastTrack(props.airportConfig.terminalPaxTypeQueueAllocation))
    else
      PaxTypeQueueAllocation(
        B5JPlusTypeAllocator(props.b5JStartDate),
        TerminalQueueAllocator(props.airportConfig.terminalPaxTypeQueueAllocation))

    val arrivalSplitsGraphStage = new ArrivalSplitsGraphStage(
      name = props.logLabel,
      props.airportConfig.portCode,
      optionalInitialFlights = initialFlightsWithSplits,
      splitsCalculator = manifests.queues.SplitsCalculator(
        props.airportConfig.feedPortCode,
        ptqa,
        props.airportConfig.defaultPaxSplits.splits.toSet
      ),
      groupFlightsByCodeShares = groupFlightsByCodeShares,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now)

    val staffGraphStage = new StaffGraphStage(
      name = props.logLabel,
      initialShifts = props.initialShifts,
      initialFixedPoints = props.initialFixedPoints,
      optionalInitialMovements = Option(props.initialStaffMovements),
      initialStaffMinutes = maybeStaffMinutes.getOrElse(StaffMinutes(Seq())),
      now = props.now,
      expireAfterMillis = props.expireAfterMillis,
      airportConfig = props.airportConfig,
      numberOfDays = props.maxDaysToCrunch,
      checkRequiredUpdatesOnStartup = props.checkRequiredStaffUpdatesOnStartup)

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

    val liveStateDaysAhead = 2

    val portStateGraphStage = new PortStateGraphStage(
      name = props.logLabel,
      optionalInitialPortState = props.initialPortState,
      airportConfig = props.airportConfig,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      liveDaysAhead = liveStateDaysAhead)

    val crunchSystem = RunnableCrunch(
      props.arrivalsForecastBaseSource, props.arrivalsForecastSource, props.arrivalsLiveBaseSource, props.arrivalsLiveSource,
      props.manifestsLiveSource, props.manifestResponsesSource,
      shiftsSource, fixedPointsSource, staffMovementsSource,
      actualDesksAndQueuesSource,
      arrivalsStage, arrivalSplitsGraphStage,
      workloadGraphStage, loadBatcher, crunchLoadGraphStage,
      staffGraphStage, staffBatcher, simulationGraphStage,
      portStateGraphStage,
      forecastArrivalsDiffingStage, liveBaseArrivalsDiffingStage, liveArrivalsDiffingStage,
      props.actors("forecast-base-arrivals").actorRef, props.actors("forecast-arrivals").actorRef, props.actors("live-base-arrivals").actorRef, props.actors("live-arrivals").actorRef,
      props.voyageManifestsActor, props.manifestRequestsSink,
      props.liveCrunchStateActor, props.forecastCrunchStateActor,
      props.actors("aggregated-arrivals").actorRef,
      crunchStartDateProvider, props.now, props.airportConfig.queues, liveStateDaysAhead, forecastMaxMillis
    )

    val (forecastBaseIn, forecastIn, liveBaseIn, liveIn, manifestsLiveIn, shiftsIn, fixedPointsIn, movementsIn, actDesksIn, arrivalsKillSwitch, manifestsKillSwitch, shiftsKS, fixedPKS, movementsKS) = crunchSystem.run

    CrunchSystem(
      shifts = shiftsIn,
      fixedPoints = fixedPointsIn,
      staffMovements = movementsIn,
      forecastBaseArrivalsResponse = forecastBaseIn,
      forecastArrivalsResponse = forecastIn,
      liveBaseArrivalsResponse = liveBaseIn,
      liveArrivalsResponse = liveIn,
      manifestsLiveResponse = manifestsLiveIn,
      actualDeskStats = actDesksIn,
      List(arrivalsKillSwitch, manifestsKillSwitch, shiftsKS, fixedPKS, movementsKS)
    )
  }

  def initialStaffMinutesFromPortState(initialPortState: Option[PortState]): Option[StaffMinutes] = initialPortState.map(
    ps => StaffMinutes(ps.staffMinutes))

  def initialCrunchMinutesFromPortState(initialPortState: Option[PortState]): Option[CrunchMinutes] = initialPortState.map(
    ps => CrunchMinutes(ps.crunchMinutes.values.toSet))

  def initialLoadsFromPortState(initialPortState: Option[PortState]): Option[Loads] = initialPortState.map(ps => Loads.fromCrunchMinutes(ps.crunchMinutes))

  def initialFlightsFromPortState(initialPortState: Option[PortState]): Option[FlightsWithSplits] = initialPortState.map { ps =>
    val flightsWithSplits = ps.flights.values.toSeq

    FlightsWithSplits(flightsWithSplits, Seq())
  }
}
