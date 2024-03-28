package services.crunch

import actors.routing.FeedArrivalsRouterActor.FeedArrivals
import akka.actor.ActorRef
import akka.pattern.StatusReply.Ack
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared.CrunchApi._
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}
import uk.gov.homeoffice.drt.arrivals.FeedArrival
import uk.gov.homeoffice.drt.feeds.FeedStatus
import uk.gov.homeoffice.drt.ports.{AclFeedSource, FeedSource, ForecastFeedSource, LiveBaseFeedSource, LiveFeedSource}

import scala.concurrent.{ExecutionContext, Future}

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply[FR, MS, SAD](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                         forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],
                         liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                         liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                         manifestsLiveSource: Source[ManifestsFeedResponse, MS],
                         actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                         forecastBaseArrivalsActor: ActorRef,
                         forecastArrivalsActor: ActorRef,
                         liveBaseArrivalsActor: ActorRef,
                         liveArrivalsActor: ActorRef,
                         updateFeedStatus: (FeedSource, ArrivalsFeedResponse) => Unit,
                         applyPaxDeltas: List[FeedArrival] => Future[List[FeedArrival]],
                         manifestsActor: ActorRef,
                         portStateActor: ActorRef,
                         forecastMaxMillis: () => MillisSinceEpoch
                        )
                        (implicit ec: ExecutionContext): RunnableGraph[(FR, FR, FR, FR, MS, SAD, UniqueKillSwitch, UniqueKillSwitch)] = {

    val arrivalsKillSwitch = KillSwitches.single[FeedArrivals]
    val manifestsLiveKillSwitch = KillSwitches.single[ManifestsFeedResponse]

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.createGraph(
      forecastBaseArrivalsSource,
      forecastArrivalsSource,
      liveBaseArrivalsSource,
      liveArrivalsSource,
      manifestsLiveSource,
      actualDesksAndWaitTimesSource,
      arrivalsKillSwitch,
      manifestsLiveKillSwitch,
    )((_, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          forecastBaseArrivalsSourceSync,
          forecastArrivalsSourceSync,
          liveBaseArrivalsSourceSync,
          liveArrivalsSourceSync,
          manifestsLiveSourceSync,
          actualDesksAndWaitTimesSourceSync,
          arrivalsKillSwitchSync,
          manifestsLiveKillSwitchSync,
        ) =>
          def ackingActorSink(actorRef: ActorRef): SinkShape[Any] =
            builder.add(Sink.actorRefWithAck(actorRef, StreamInitialized, Ack, StreamCompleted, StreamFailure).async)

          def simpleActorSink(actorRef: ActorRef): SinkShape[Any] =
            builder.add(Sink.actorRef(actorRef, StreamCompleted).async)

          val deskStatsSink = ackingActorSink(portStateActor)

          val baseArrivalsSink = simpleActorSink(forecastBaseArrivalsActor)
          val fcstArrivalsSink = simpleActorSink(forecastArrivalsActor)
          val liveBaseArrivalsSink = simpleActorSink(liveBaseArrivalsActor)
          val liveArrivalsSink = simpleActorSink(liveArrivalsActor)
          val manifestsSink = simpleActorSink(manifestsActor)

          // @formatter:off
          forecastBaseArrivalsSourceSync.out
            .wireTap(updateFeedStatus(AclFeedSource, _))
            .map {
              case ArrivalsFeedSuccess(as, _) =>
                val maxScheduledMillis = forecastMaxMillis()
                FeedArrivals(as.filter(_.scheduled < maxScheduledMillis))
              case _ =>
                FeedArrivals(List())
            }
            .mapAsync(1) {
              case FeedArrivals(as) =>
                applyPaxDeltas(as.toList).map(FeedArrivals(_))
            } ~> baseArrivalsSink

          forecastArrivalsSourceSync.out
            .wireTap(updateFeedStatus(ForecastFeedSource, _))
            .map {
              case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)
              case _ => FeedArrivals(List())
            } ~> fcstArrivalsSink

          liveBaseArrivalsSourceSync.out
            .wireTap(updateFeedStatus(LiveBaseFeedSource, _))
            .map {
              case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)
              case _ => FeedArrivals(List())
            } ~> liveBaseArrivalsSink

          liveArrivalsSourceSync.out
            .wireTap(updateFeedStatus(LiveFeedSource, _))
            .map {
              case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)
              case _ => FeedArrivals(List())
            } ~> arrivalsKillSwitchSync ~> liveArrivalsSink

          manifestsLiveSourceSync.out.collect {
            case ManifestsFeedSuccess(manifests, createdAt) =>
              ManifestsFeedSuccess(manifests, createdAt)
          } ~> manifestsLiveKillSwitchSync ~> manifestsSink

          actualDesksAndWaitTimesSourceSync.out.map(_.asContainer) ~> deskStatsSink

          // @formatter:on

          ClosedShape
    }

    RunnableGraph
      .fromGraph(graph)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(RunnableCrunch.getClass.getName))
  }
}
