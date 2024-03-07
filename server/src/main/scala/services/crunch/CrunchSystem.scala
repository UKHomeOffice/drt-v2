package services.crunch

import akka.Done
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.stream._
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import drt.chroma.ArrivalsDiffingStage
import drt.server.feeds.Feed.EnabledFeedWithFrequency
import drt.server.feeds.{Feed, ManifestsFeedResponse}
import drt.shared.CrunchApi._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import queueus._
import services.TryCrunchWholePax
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsLike}
import services.graphstages.ArrivalsGraphStage
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, FlightsWithSplitsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.crunchsystem.PersistentStateActors
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}


case class CrunchSystem[FT](forecastBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            forecastArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveArrivalsResponse: EnabledFeedWithFrequency[FT],
                            manifestsLiveResponseSource: SourceQueueWithComplete[ManifestsFeedResponse],
                            actualDeskStatsSource: SourceQueueWithComplete[ActualDeskStats],
                            flushArrivalsSource: SourceQueueWithComplete[Boolean],
                            mergeArrivalsRequestActor: ActorRef,
                            crunchRequestActor: ActorRef,
                            deskRecsRequestActor: ActorRef,
                            deploymentRequestActor: ActorRef,
                            killSwitches: List[UniqueKillSwitch]
                           )

case class CrunchProps[FT](airportConfig: AirportConfig,
                           portStateActor: ActorRef,
                           maxDaysToCrunch: Int,
                           expireAfterMillis: Int,
                           now: () => SDateLike = () => SDate.now(),
                           manifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]],
                           crunchActors: PersistentStateActors,
                           feedActors: Map[FeedSource, ActorRef],
                           manifestsRouterActor: ActorRef,
                           initialPortState: Option[PortState] = None,
                           initialForecastBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           initialForecastArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           initialLiveBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           initialLiveArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival](),
                           arrivalsForecastBaseFeed: Feed[FT],
                           arrivalsForecastFeed: Feed[FT],
                           arrivalsLiveBaseFeed: Feed[FT],
                           arrivalsLiveFeed: Feed[FT],
                           flushArrivalsSource: Source[Boolean, SourceQueueWithComplete[Boolean]],
                           flushArrivalsOnStart: Boolean,
                           refreshArrivalsOnStart: Boolean,
                           optimiser: TryCrunchWholePax,
                           startDeskRecs: () => (ActorRef, ActorRef, ActorRef, ActorRef, UniqueKillSwitch,
                             UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch),
                           arrivalsAdjustments: ArrivalsAdjustmentsLike,
                           addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff],
                           setPcpTimes: ArrivalsDiff => Future[ArrivalsDiff],
                           passengerAdjustments: List[Arrival] => Future[List[Arrival]],
                           system: ActorSystem,
                          )

object CrunchSystem {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[FT](props: CrunchProps[FT])
               (implicit materializer: Materializer, ec: ExecutionContext): CrunchSystem[FT] = {
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] =
      Source.queue[ActualDeskStats](10, OverflowStrategy.backpressure)

    val initialFlightsWithSplits = initialFlightsFromPortState(props.initialPortState)

    val forecastMaxMillis: () => MillisSinceEpoch = () => props.now().addDays(props.maxDaysToCrunch).millisSinceEpoch

    val initialMergedArrivals = SortedMap[UniqueArrival, Arrival]() ++
      initialFlightsWithSplits.map(_.flightsToUpdate.map(fws => (fws.apiFlight.unique, fws.apiFlight))).getOrElse(List())

//    val arrivalsStage = new ArrivalsGraphStage(
//      initialForecastBaseArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastBaseArrivals,
//      initialForecastArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals,
//      initialLiveBaseArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals,
//      initialLiveArrivals = if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveArrivals,
//      initialMergedArrivals = initialMergedArrivals,
//      validPortTerminals = props.airportConfig.terminals.toSet,
//      ArrivalDataSanitiser(
//        props.airportConfig.maybeCiriumEstThresholdHours,
//        props.airportConfig.maybeCiriumTaxiThresholdMinutes
//      ),
//      arrivalsAdjustments = props.arrivalsAdjustments,
//      expireAfterMillis = props.expireAfterMillis,
//      now = props.now,
//      flushOnStart = props.flushArrivalsOnStart,
//    )

    val forecastArrivalsDiffingStage = new ArrivalsDiffingStage(
      if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialForecastArrivals, forecastMaxMillis)
    val liveBaseArrivalsDiffingStage = new ArrivalsDiffingStage(
      if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveBaseArrivals, forecastMaxMillis)
    val liveArrivalsDiffingStage = new ArrivalsDiffingStage(
      if (props.refreshArrivalsOnStart) SortedMap[UniqueArrival, Arrival]() else props.initialLiveArrivals, forecastMaxMillis)

    val (
      mergeArrivalsQueueActor,
      crunchQueueActor,
      deskRecsQueueActor,
      deploymentQueueActor,
      mergeArrivalsKillSwitch,
      deskRecsKillSwitch,
      deploymentsKillSwitch,
      staffingUpdateKillSwitch) = props.startDeskRecs()

    val runnableCrunch = RunnableCrunch(
      forecastBaseArrivalsSource = props.arrivalsForecastBaseFeed.source,
      forecastArrivalsSource = props.arrivalsForecastFeed.source,
      liveBaseArrivalsSource = props.arrivalsLiveBaseFeed.source,
      liveArrivalsSource = props.arrivalsLiveFeed.source,
      manifestsLiveSource = props.manifestsLiveSource,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
//      arrivalsGraphStage = arrivalsStage,
      forecastArrivalsDiffStage = forecastArrivalsDiffingStage,
      liveBaseArrivalsDiffStage = liveBaseArrivalsDiffingStage,
      liveArrivalsDiffStage = liveArrivalsDiffingStage,
      forecastBaseArrivalsActor = props.feedActors(AclFeedSource),
      forecastArrivalsActor = props.feedActors(ForecastFeedSource),
      liveBaseArrivalsActor = props.feedActors(LiveBaseFeedSource),
      liveArrivalsActor = props.feedActors(LiveFeedSource),
      applyPaxDeltas = props.passengerAdjustments,
      manifestsActor = props.manifestsRouterActor,
      portStateActor = props.portStateActor,
      aggregatedArrivalsStateActor = props.crunchActors.aggregatedArrivalsActor,
      forecastMaxMillis = forecastMaxMillis,
      flushArrivalsSource = props.flushArrivalsSource,
      addArrivalPredictions = props.addArrivalPredictions,
      setPcpTimes = props.setPcpTimes,
    )

    val (
      forecastBaseIn,
      forecastIn,
      liveBaseIn,
      liveIn,
      manifestsLiveIn,
      actDesksIn,
      flushArrivalsIn,
      arrivalsKillSwitch,
      manifestsKillSwitch) = runnableCrunch.run()

    val killSwitches = List(arrivalsKillSwitch, manifestsKillSwitch, mergeArrivalsKillSwitch, deskRecsKillSwitch, deploymentsKillSwitch, staffingUpdateKillSwitch)

    CoordinatedShutdown(props.system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdown-crunch") { () =>
      log.info("Shutting down crunch system")

      killSwitches.foreach(_.shutdown())

      Future.successful(Done)
    }

    CrunchSystem(
      forecastBaseArrivalsResponse =
        EnabledFeedWithFrequency(forecastBaseIn, props.arrivalsForecastBaseFeed.initialDelay, props.arrivalsForecastBaseFeed.interval),
      forecastArrivalsResponse =
        EnabledFeedWithFrequency(forecastIn, props.arrivalsForecastFeed.initialDelay, props.arrivalsForecastFeed.interval),
      liveBaseArrivalsResponse =
        EnabledFeedWithFrequency(liveBaseIn, props.arrivalsLiveBaseFeed.initialDelay, props.arrivalsLiveBaseFeed.interval),
      liveArrivalsResponse =
        EnabledFeedWithFrequency(liveIn, props.arrivalsLiveFeed.initialDelay, props.arrivalsLiveFeed.interval),
      manifestsLiveResponseSource = manifestsLiveIn,
      actualDeskStatsSource = actDesksIn,
      flushArrivalsSource = flushArrivalsIn,
      mergeArrivalsRequestActor = mergeArrivalsQueueActor,
      crunchRequestActor = crunchQueueActor,
      deskRecsRequestActor = deskRecsQueueActor,
      deploymentRequestActor = deploymentQueueActor,
      killSwitches
    )
  }

  def paxTypeQueueAllocator(config: AirportConfig): PaxTypeQueueAllocation = if (config.hasTransfer)
    PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator,
      TerminalQueueAllocator(config.terminalPaxTypeQueueAllocation))
  else
    PaxTypeQueueAllocation(
      B5JPlusTypeAllocator,
      TerminalQueueAllocator(config.terminalPaxTypeQueueAllocation))

  private def initialFlightsFromPortState(initialPortState: Option[PortState]): Option[FlightsWithSplitsDiff] = initialPortState.map { ps =>
    FlightsWithSplitsDiff(ps.flights.values.toList, List())
  }
}
