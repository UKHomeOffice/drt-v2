package services.crunch

import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Source, SourceQueueWithComplete}
import drt.server.feeds.Feed.EnabledFeedWithFrequency
import drt.server.feeds.{ArrivalsFeedResponse, Feed}
import drt.shared.CrunchApi._
import org.slf4j.{Logger, LoggerFactory}
import queueus._
import uk.gov.homeoffice.drt.arrivals.FeedArrival
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}


case class CrunchSystem[FT](forecastBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            forecastArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveBaseArrivalsResponse: EnabledFeedWithFrequency[FT],
                            liveArrivalsResponse: EnabledFeedWithFrequency[FT],
                            actualDeskStatsSource: SourceQueueWithComplete[ActualDeskStats],
                            mergeArrivalsRequestQueueActor: ActorRef,
                            crunchRequestQueueActor: ActorRef,
                            deskRecsRequestQueueActor: ActorRef,
                            deploymentRequestQueueActor: ActorRef,
                            killSwitches: List[UniqueKillSwitch]
                           )

case class CrunchProps[FT](portStateActor: ActorRef,
                           maxDaysToCrunch: Int,
                           now: () => SDateLike = () => SDate.now(),
                           feedActors: Map[FeedSource, ActorRef],
                           updateFeedStatus: (FeedSource, ArrivalsFeedResponse) => Unit,
                           arrivalsForecastBaseFeed: Feed[FT],
                           arrivalsForecastFeed: Feed[FT],
                           arrivalsLiveBaseFeed: Feed[FT],
                           arrivalsLiveFeed: Feed[FT],
                           startDeskRecs: () => (ActorRef, ActorRef, ActorRef, ActorRef, Iterable[UniqueKillSwitch]),
                           passengerAdjustments: List[FeedArrival] => Future[List[FeedArrival]],
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
      killSwitches,
      ) = props.startDeskRecs()

    val runnableCrunch = RunnableCrunch(
      forecastBaseArrivalsSource = props.arrivalsForecastBaseFeed.source,
      forecastArrivalsSource = props.arrivalsForecastFeed.source,
      liveBaseArrivalsSource = props.arrivalsLiveBaseFeed.source,
      liveArrivalsSource = props.arrivalsLiveFeed.source,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
      forecastBaseArrivalsActor = props.feedActors(AclFeedSource),
      forecastArrivalsActor = props.feedActors(ForecastFeedSource),
      liveBaseArrivalsActor = props.feedActors(LiveBaseFeedSource),
      liveArrivalsActor = props.feedActors(LiveFeedSource),
      updateFeedStatus = props.updateFeedStatus,
      applyPaxDeltas = props.passengerAdjustments,
      portStateActor = props.portStateActor,
      forecastMaxMillis = forecastMaxMillis,
    )

    val (
      forecastBaseIn,
      forecastIn,
      liveBaseIn,
      liveIn,
      actDesksIn,
      arrivalsKillSwitch,
      ) = runnableCrunch.run()

    val allKillSwitches = List(arrivalsKillSwitch) ++ killSwitches

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
      actualDeskStatsSource = actDesksIn,
      mergeArrivalsRequestQueueActor = mergeArrivalsRequestQueueActor,
      crunchRequestQueueActor = crunchRequestQueueActor,
      deskRecsRequestQueueActor = deskRecsRequestQueueActor,
      deploymentRequestQueueActor = deploymentRequestQueueActor,
      allKillSwitches,
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
