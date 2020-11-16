package services.crunch

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import drt.chroma.ArrivalsDiffingStage
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.api.Arrival
import drt.shared.{SDateLike, _}
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import queueus._
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.SplitsProvider.SplitProvider
import services._
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsLike}
import services.graphstages.Crunch._
import services.graphstages._

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


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

case class CrunchProps[FR](
                            logLabel: String = "",
                            airportConfig: AirportConfig,
                            pcpArrival: Arrival => MilliDate,
                            historicalSplitsProvider: SplitProvider,
                            portStateActor: ActorRef,
                            maxDaysToCrunch: Int,
                            expireAfterMillis: Int,
                            crunchOffsetMillis: MillisSinceEpoch = 0,
                            actors: Map[String, ActorRef],
                            useNationalityBasedProcessingTimes: Boolean,
                            useLegacyManifests: Boolean = false,
                            now: () => SDateLike = () => SDate.now(),
                            initialFlightsWithSplits: Option[FlightsWithSplitsDiff] = None,
                            manifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]],
                            manifestResponsesSource: Source[List[BestAvailableManifest], NotUsed],
                            voyageManifestsActor: ActorRef,
                            manifestRequestsSink: Sink[List[Arrival], NotUsed],
                            initialPortState: Option[PortState] = None,
                            initialForecastBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                            initialForecastArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                            initialLiveBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                            initialLiveArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                            arrivalsForecastBaseSource: Source[ArrivalsFeedResponse, FR], arrivalsForecastSource: Source[ArrivalsFeedResponse, FR],
                            pcpPaxFn: Arrival => Int,
                            arrivalsLiveBaseSource: Source[ArrivalsFeedResponse, FR],
                            arrivalsLiveSource: Source[ArrivalsFeedResponse, FR],
                            passengersActorProvider: () => ActorRef,
                            initialShifts: ShiftAssignments = ShiftAssignments(Seq()),
                            initialFixedPoints: FixedPointAssignments = FixedPointAssignments(Seq()),
                            initialStaffMovements: Seq[StaffMovement] = Seq(),
                            refreshArrivalsOnStart: Boolean,
                            stageThrottlePer: FiniteDuration,
                            adjustEGateUseByUnder12s: Boolean,
                            optimiser: TryCrunch,
                            aclPaxAdjustmentDays: Int,
                            startDeskRecs: () => (UniqueKillSwitch, UniqueKillSwitch),
                            arrivalsAdjustments: ArrivalsAdjustmentsLike
                          )

object CrunchSystem {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[FR](props: CrunchProps[FR])
               (implicit materializer: Materializer, system: ActorSystem, ec: ExecutionContext): CrunchSystem[FR] = {

    val shiftsSource: Source[ShiftAssignments, SourceQueueWithComplete[ShiftAssignments]] = Source.queue[ShiftAssignments](10, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[FixedPointAssignments, SourceQueueWithComplete[FixedPointAssignments]] = Source.queue[FixedPointAssignments](10, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](10, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](10, OverflowStrategy.backpressure)

    val initialFlightsWithSplits = initialFlightsFromPortState(props.initialPortState)

    val forecastMaxMillis: () => MillisSinceEpoch = () => props.now().addDays(props.maxDaysToCrunch).millisSinceEpoch

    val initialMergedArrivals = SortedMap[UniqueArrival, Arrival]() ++ initialFlightsWithSplits.map(_.flightsToUpdate.map(fws => (fws.apiFlight.unique, fws.apiFlight))).getOrElse(List())

    val arrivalsStage = new ArrivalsGraphStage(
      name = props.logLabel,
      initialForecastBaseArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastBaseArrivals,
      initialForecastArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals,
      initialLiveBaseArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals,
      initialLiveArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveArrivals,
      initialMergedArrivals = initialMergedArrivals,
      pcpArrivalTime = props.pcpArrival,
      validPortTerminals = props.airportConfig.terminals.toSet,
      ArrivalDataSanitiser(
        props.airportConfig.maybeCiriumEstThresholdHours,
        props.airportConfig.maybeCiriumTaxiThresholdMinutes
      ),
      arrivalsAdjustments = props.arrivalsAdjustments,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now)

    val forecastArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals, forecastMaxMillis)
    val liveBaseArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals, forecastMaxMillis)
    val liveArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveArrivals, forecastMaxMillis)

    val ptqa = if (props.airportConfig.hasTransfer)
      PaxTypeQueueAllocation(
        B5JPlusWithTransitTypeAllocator(),
        TerminalQueueAllocator(props.airportConfig.terminalPaxTypeQueueAllocation))
    else
      PaxTypeQueueAllocation(
        B5JPlusTypeAllocator(),
        TerminalQueueAllocator(props.airportConfig.terminalPaxTypeQueueAllocation))

    val splitAdjustments = if (props.adjustEGateUseByUnder12s)
      ChildEGateAdjustments(props.airportConfig.assumedAdultsPerChild)
    else
      AdjustmentsNoop()

    val arrivalSplitsGraphStage = new ArrivalSplitsGraphStage(
      name = props.logLabel,
      optionalInitialFlights = initialFlightsWithSplits,
      splitsCalculator = manifests.queues.SplitsCalculator(ptqa, props.airportConfig.terminalPaxSplits, splitAdjustments),
      expireAfterMillis = props.expireAfterMillis,
      now = props.now
    )

    val staffGraphStage = new StaffGraphStage(
      initialShifts = props.initialShifts,
      initialFixedPoints = props.initialFixedPoints,
      optionalInitialMovements = Option(props.initialStaffMovements),
      now = props.now,
      expireAfterMillis = props.expireAfterMillis,
      numberOfDays = props.maxDaysToCrunch)

    val crunchSystem = RunnableCrunch(
      forecastBaseArrivalsSource = props.arrivalsForecastBaseSource,
      forecastArrivalsSource = props.arrivalsForecastSource,
      liveBaseArrivalsSource = props.arrivalsLiveBaseSource,
      liveArrivalsSource = props.arrivalsLiveSource,
      manifestsLiveSource = props.manifestsLiveSource,
      manifestResponsesSource = props.manifestResponsesSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
      arrivalsGraphStage = arrivalsStage,
      arrivalSplitsStage = arrivalSplitsGraphStage,
      staffGraphStage = staffGraphStage,
      forecastArrivalsDiffStage = forecastArrivalsDiffingStage,
      liveBaseArrivalsDiffStage = liveBaseArrivalsDiffingStage,
      liveArrivalsDiffStage = liveArrivalsDiffingStage,
      forecastBaseArrivalsActor = props.actors("forecast-base-arrivals"),
      forecastArrivalsActor = props.actors("forecast-arrivals"),
      liveBaseArrivalsActor = props.actors("live-base-arrivals"),
      liveArrivalsActor = props.actors("live-arrivals"),
      applyPaxDeltas = PaxDeltas.applyAdjustmentsToArrivals(props.passengersActorProvider, props.aclPaxAdjustmentDays),
      manifestsActor = props.voyageManifestsActor,
      manifestRequestsSink = props.manifestRequestsSink,
      portStateActor = props.portStateActor,
      aggregatedArrivalsStateActor = props.actors("aggregated-arrivals"),
      deploymentRequestActor = props.actors("deployment-request"),
      forecastMaxMillis = forecastMaxMillis,
      throttleDurationPer = props.stageThrottlePer
    )

    val (forecastBaseIn, forecastIn, liveBaseIn, liveIn, manifestsLiveIn, shiftsIn, fixedPointsIn, movementsIn, actDesksIn, arrivalsKillSwitch, manifestsKillSwitch, shiftsKS, fixedPKS, movementsKS) = crunchSystem.run

    val (deskRecsKillSwitch, deploymentsKillSwitch) = props.startDeskRecs()

    val killSwitches = List(arrivalsKillSwitch, manifestsKillSwitch, shiftsKS, fixedPKS, movementsKS, deskRecsKillSwitch, deploymentsKillSwitch)

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
      killSwitches
    )
  }

  def initialStaffMinutesFromPortState(initialPortState: Option[PortState]): Option[StaffMinutes] = initialPortState.map(
    ps => StaffMinutes(ps.staffMinutes))

  def initialCrunchMinutesFromPortState(initialPortState: Option[PortState]): Option[CrunchMinutes] = initialPortState.map(
    ps => CrunchMinutes(ps.crunchMinutes.values.toSet))

  def initialLoadsFromPortState(initialPortState: Option[PortState]): Option[Loads] = initialPortState.map(ps => Loads.fromCrunchMinutes(ps.crunchMinutes))

  def initialFlightsFromPortState(initialPortState: Option[PortState]): Option[FlightsWithSplitsDiff] = initialPortState.map { ps =>
    FlightsWithSplitsDiff(ps.flights.values.toList, List())
  }
}
