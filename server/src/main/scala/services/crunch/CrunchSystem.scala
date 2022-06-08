package services.crunch

import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import drt.chroma.ArrivalsDiffingStage
import drt.server.feeds.Feed
import drt.server.feeds.Feed.EnabledFeedWithFrequency
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import queueus._
import server.feeds.ManifestsFeedResponse
import services._
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsLike}
import services.graphstages.Crunch._
import services.graphstages._
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.{AirportConfig, ForecastFeedSource, LiveBaseFeedSource, LiveFeedSource}
import uk.gov.homeoffice.drt.redlist.{RedListUpdateCommand, RedListUpdates}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}


case class CrunchSystem[FT](shifts: SourceQueueWithComplete[ShiftAssignments],
                            fixedPoints: SourceQueueWithComplete[FixedPointAssignments],
                            staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                            forecastBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            forecastArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveArrivalsResponse: EnabledFeedWithFrequency[FT],
                            manifestsLiveResponse: SourceQueueWithComplete[ManifestsFeedResponse],
                            actualDeskStats: SourceQueueWithComplete[ActualDeskStats],
                            redListUpdates: SourceQueueWithComplete[List[RedListUpdateCommand]],
                            crunchRequestActor: ActorRef,
                            deploymentRequestActor: ActorRef,
                            killSwitches: List[UniqueKillSwitch]
                           )

case class CrunchProps[FT](logLabel: String = "",
                           airportConfig: AirportConfig,
                           portStateActor: ActorRef,
                           flightsActor: ActorRef,
                           maxDaysToCrunch: Int,
                           expireAfterMillis: Int,
                           crunchOffsetMillis: MillisSinceEpoch = 0,
                           actors: Map[String, ActorRef],
                           useNationalityBasedProcessingTimes: Boolean,
                           now: () => SDateLike = () => SDate.now(),
                           initialFlightsWithSplits: Option[FlightsWithSplitsDiff] = None,
                           manifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]],
                           voyageManifestsActor: ActorRef,
                           simulator: TrySimulator,
                           initialPortState: Option[PortState] = None,
                           initialForecastBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           initialForecastArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           initialLiveBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           initialLiveArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           arrivalsForecastBaseFeed: Feed[FT],
                           arrivalsForecastFeed: Feed[FT],
                           arrivalsLiveBaseFeed: Feed[FT],
                           arrivalsLiveFeed: Feed[FT],
                           redListUpdatesSource: Source[List[RedListUpdateCommand], SourceQueueWithComplete[List[RedListUpdateCommand]]],
                           passengersActorProvider: () => ActorRef,
                           initialShifts: ShiftAssignments = ShiftAssignments(Seq()),
                           initialFixedPoints: FixedPointAssignments = FixedPointAssignments(Seq()),
                           initialStaffMovements: Seq[StaffMovement] = Seq(),
                           flushArrivalsOnStart: Boolean,
                           refreshArrivalsOnStart: Boolean,
                           refreshManifestsOnStart: Boolean,
                           optimiser: TryCrunch,
                           aclPaxAdjustmentDays: Int,
                           startDeskRecs: () => (ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch),
                           arrivalsAdjustments: ArrivalsAdjustmentsLike,
                           addTouchdownPredictions: ArrivalsDiff => Future[ArrivalsDiff],
                           setPcpTimes: ArrivalsDiff => Future[ArrivalsDiff],
                          )

object CrunchSystem {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[FT](props: CrunchProps[FT])
               (implicit materializer: Materializer, ec: ExecutionContext): CrunchSystem[FT] = {

    val shiftsSource: Source[ShiftAssignments, SourceQueueWithComplete[ShiftAssignments]] = Source.queue[ShiftAssignments](10, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[FixedPointAssignments, SourceQueueWithComplete[FixedPointAssignments]] = Source.queue[FixedPointAssignments](10, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](10, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](10, OverflowStrategy.backpressure)

    val initialFlightsWithSplits = initialFlightsFromPortState(props.initialPortState)

    val forecastMaxMillis: () => MillisSinceEpoch = () => props.now().addDays(props.maxDaysToCrunch).millisSinceEpoch

    val initialMergedArrivals = SortedMap[UniqueArrival, Arrival]() ++ initialFlightsWithSplits.map(_.flightsToUpdate.map(fws => (fws.apiFlight.unique, fws.apiFlight))).getOrElse(List())

    val arrivalsStage = new ArrivalsGraphStage(
      name = props.logLabel,
      initialRedListUpdates = RedListUpdates.empty,
      initialForecastBaseArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastBaseArrivals,
      initialForecastArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals,
      initialLiveBaseArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals,
      initialLiveArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveArrivals,
      initialMergedArrivals = initialMergedArrivals,
      validPortTerminals = props.airportConfig.terminals.toSet,
      ArrivalDataSanitiser(
        props.airportConfig.maybeCiriumEstThresholdHours,
        props.airportConfig.maybeCiriumTaxiThresholdMinutes
      ),
      arrivalsAdjustments = props.arrivalsAdjustments,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now,
      flushOnStart = props.flushArrivalsOnStart,
    )

    val forecastArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals, forecastMaxMillis)
    val liveBaseArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals, forecastMaxMillis)
    val liveArrivalsDiffingStage = new ArrivalsDiffingStage(if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveArrivals, forecastMaxMillis)

    val staffGraphStage = new StaffGraphStage(
      initialShifts = props.initialShifts,
      initialFixedPoints = props.initialFixedPoints,
      optionalInitialMovements = Option(props.initialStaffMovements),
      now = props.now,
      expireAfterMillis = props.expireAfterMillis,
      numberOfDays = props.maxDaysToCrunch)

    val (crunchQueueActor, deploymentQueueActor, deskRecsKillSwitch, deploymentsKillSwitch) = props.startDeskRecs()

    val crunchSystem = RunnableCrunch(
      forecastBaseArrivalsSource = props.arrivalsForecastBaseFeed.source,
      forecastArrivalsSource = props.arrivalsForecastFeed.source,
      liveBaseArrivalsSource = props.arrivalsLiveBaseFeed.source,
      liveArrivalsSource = props.arrivalsLiveFeed.source,
      manifestsLiveSource = props.manifestsLiveSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
      arrivalsGraphStage = arrivalsStage,
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
      portStateActor = props.portStateActor,
      aggregatedArrivalsStateActor = props.actors("aggregated-arrivals"),
      deploymentRequestActor = deploymentQueueActor,
      forecastMaxMillis = forecastMaxMillis,
      redListUpdatesSource = props.redListUpdatesSource,
      addTouchdownPredictions = props.addTouchdownPredictions,
      setPcpTimes = props.setPcpTimes,
    )

    val (forecastBaseIn, forecastIn, liveBaseIn, liveIn, manifestsLiveIn, shiftsIn, fixedPointsIn, movementsIn, actDesksIn, redListUpdatesIn, arrivalsKillSwitch, manifestsKillSwitch, shiftsKS, fixedPKS, movementsKS) = crunchSystem.run

    val killSwitches = List(arrivalsKillSwitch, manifestsKillSwitch, shiftsKS, fixedPKS, movementsKS, deskRecsKillSwitch, deploymentsKillSwitch)

    CrunchSystem(
      shifts = shiftsIn,
      fixedPoints = fixedPointsIn,
      staffMovements = movementsIn,
      forecastBaseArrivalsResponse = EnabledFeedWithFrequency(forecastBaseIn, props.arrivalsForecastBaseFeed.initialDelay, props.arrivalsForecastBaseFeed.interval),
      forecastArrivalsResponse = EnabledFeedWithFrequency(forecastIn, props.arrivalsForecastFeed.initialDelay, props.arrivalsForecastFeed.interval),
      liveBaseArrivalsResponse = EnabledFeedWithFrequency(liveBaseIn, props.arrivalsLiveBaseFeed.initialDelay, props.arrivalsLiveBaseFeed.interval),
      liveArrivalsResponse = EnabledFeedWithFrequency(liveIn, props.arrivalsLiveFeed.initialDelay, props.arrivalsLiveFeed.interval),
      manifestsLiveResponse = manifestsLiveIn,
      actualDeskStats = actDesksIn,
      redListUpdates = redListUpdatesIn,
      crunchRequestActor = crunchQueueActor,
      deploymentRequestActor = deploymentQueueActor,
      killSwitches
    )
  }

  def paxTypeQueueAllocator[FR](config: AirportConfig): PaxTypeQueueAllocation = if (config.hasTransfer)
    PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator,
      TerminalQueueAllocator(config.terminalPaxTypeQueueAllocation))
  else
    PaxTypeQueueAllocation(
      B5JPlusTypeAllocator,
      TerminalQueueAllocator(config.terminalPaxTypeQueueAllocation))

  def initialStaffMinutesFromPortState(initialPortState: Option[PortState]): Option[StaffMinutes] = initialPortState.map(
    ps => StaffMinutes(ps.staffMinutes))

  def initialCrunchMinutesFromPortState(initialPortState: Option[PortState]): Option[CrunchMinutes] = initialPortState.map(
    ps => CrunchMinutes(ps.crunchMinutes.values.toSet))

  def initialLoadsFromPortState(initialPortState: Option[PortState]): Option[Loads] = initialPortState.map(ps => Loads.fromCrunchMinutes(ps.crunchMinutes))

  def initialFlightsFromPortState(initialPortState: Option[PortState]): Option[FlightsWithSplitsDiff] = initialPortState.map { ps =>
    FlightsWithSplitsDiff(ps.flights.values.toList, List())
  }
}
