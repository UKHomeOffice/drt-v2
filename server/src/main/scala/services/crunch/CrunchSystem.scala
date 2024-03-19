package services.crunch

import akka.Done
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.stream._
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import drt.server.feeds.Feed.EnabledFeedWithFrequency
import drt.server.feeds.{Feed, ManifestsFeedResponse}
import drt.shared.CrunchApi._
import org.slf4j.{Logger, LoggerFactory}
import queueus._
import services.TryCrunchWholePax
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff}
import uk.gov.homeoffice.drt.crunchsystem.PersistentStateActors
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}


case class CrunchSystem[FT](forecastBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            forecastArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveArrivalsResponse: EnabledFeedWithFrequency[FT],
                            manifestsLiveResponseSource: SourceQueueWithComplete[ManifestsFeedResponse],
                            actualDeskStatsSource: SourceQueueWithComplete[ActualDeskStats],
                            mergeArrivalsRequestQueueActor: ActorRef,
                            crunchRequestQueueActor: ActorRef,
                            deskRecsRequestQueueActor: ActorRef,
                            deploymentRequestQueueActor: ActorRef,
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
                           arrivalsForecastBaseFeed: Feed[FT],
                           arrivalsForecastFeed: Feed[FT],
                           arrivalsLiveBaseFeed: Feed[FT],
                           arrivalsLiveFeed: Feed[FT],
                           optimiser: TryCrunchWholePax,
                           startDeskRecs: () => (ActorRef, ActorRef, ActorRef, ActorRef, UniqueKillSwitch,
                             UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch),
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

    val forecastMaxMillis: () => MillisSinceEpoch = () => props.now().addDays(props.maxDaysToCrunch).millisSinceEpoch

    val (
      mergeArrivalsRequestQueueActor,
      crunchRequestQueueActor,
      deskRecsRequestQueueActor,
      deploymentRequestQueueActor,
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
      forecastBaseArrivalsActor = props.feedActors(AclFeedSource),
      forecastArrivalsActor = props.feedActors(ForecastFeedSource),
      liveBaseArrivalsActor = props.feedActors(LiveBaseFeedSource),
      liveArrivalsActor = props.feedActors(LiveFeedSource),
      applyPaxDeltas = props.passengerAdjustments,
      manifestsActor = props.manifestsRouterActor,
      portStateActor = props.portStateActor,
      forecastMaxMillis = forecastMaxMillis,
    )

    val (
      forecastBaseIn,
      forecastIn,
      liveBaseIn,
      liveIn,
      manifestsLiveIn,
      actDesksIn,
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
      mergeArrivalsRequestQueueActor = mergeArrivalsRequestQueueActor,
      crunchRequestQueueActor = crunchRequestQueueActor,
      deskRecsRequestQueueActor = deskRecsRequestQueueActor,
      deploymentRequestQueueActor = deploymentRequestQueueActor,
      killSwitches,
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
}
