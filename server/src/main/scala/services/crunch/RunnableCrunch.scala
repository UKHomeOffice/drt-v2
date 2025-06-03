package services.crunch

import actors.routing.FeedArrivalsRouterActor.FeedArrivals
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.StatusReply.Ack
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.shared.CrunchApi._
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}
import uk.gov.homeoffice.drt.arrivals.FeedArrival
import uk.gov.homeoffice.drt.ports._

import scala.concurrent.{ExecutionContext, Future}

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply[FR, SAD](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                     forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],
                     liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                     liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                     actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                     forecastBaseArrivalsActor: ActorRef,
                     forecastArrivalsActor: ActorRef,
                     liveBaseArrivalsActor: ActorRef,
                     liveArrivalsActor: ActorRef,
                     updateFeedStatus: (FeedSource, ArrivalsFeedResponse) => Unit,
                     applyPaxDeltas: List[FeedArrival] => Future[List[FeedArrival]],
                     portStateActor: ActorRef,
                     forecastMaxMillis: () => MillisSinceEpoch
                    )
                    (implicit ec: ExecutionContext): RunnableGraph[(FR, FR, FR, FR, SAD, UniqueKillSwitch)] = {

    val arrivalsKillSwitch = KillSwitches.single[FeedArrivals]

    import org.apache.pekko.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.createGraph(
      forecastBaseArrivalsSource,
      forecastArrivalsSource,
      liveBaseArrivalsSource,
      liveArrivalsSource,
      actualDesksAndWaitTimesSource,
      arrivalsKillSwitch,
    )((_, _, _, _, _, _)) {

      implicit builder =>
        (
          forecastBaseArrivalsSourceSync,
          forecastArrivalsSourceSync,
          liveBaseArrivalsSourceSync,
          liveArrivalsSourceSync,
          actualDesksAndWaitTimesSourceSync,
          arrivalsKillSwitchSync,
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

          val forecastBaseBroadcast = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val forecastBroadcast = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveBaseBroadcast = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveBroadcast = builder.add(Broadcast[ArrivalsFeedResponse](2))

          val forecastBaseStatusSink = builder.add(Sink.foreach(updateFeedStatus(AclFeedSource, _)))
          val forecastStatusSink = builder.add(Sink.foreach(updateFeedStatus(ForecastFeedSource, _)))
          val liveBaseStatusSink = builder.add(Sink.foreach(updateFeedStatus(LiveBaseFeedSource, _)))
          val liveStatusSink = builder.add(Sink.foreach(updateFeedStatus(LiveFeedSource, _)))

          // @formatter:off
          forecastBaseArrivalsSourceSync ~> forecastBaseBroadcast
          forecastBaseBroadcast ~> forecastBaseStatusSink
          forecastBaseBroadcast
            .map {
              case ArrivalsFeedSuccess(as, _) =>
                println(s"Got some base forecast arrivals: ${as.size}")
                val maxScheduledMillis = forecastMaxMillis()
                FeedArrivals(as.filter(_.scheduled < maxScheduledMillis))
              case _ =>
                FeedArrivals(List())
            }
            .mapAsync(1) {
              case FeedArrivals(as) =>
                println(s"Applying pax deltas to base forecast arrivals: ${as.size}")
                applyPaxDeltas(as.toList).map{ a =>
                  println(s"Applied pax deltas to base forecast arrivals: ${a.size}")
                  FeedArrivals(a)
                }
            } ~> baseArrivalsSink

          forecastArrivalsSourceSync ~> forecastBroadcast
          forecastBroadcast ~> forecastStatusSink
          forecastBroadcast
            .map {
              case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)
              case _ => FeedArrivals(List())
            } ~> fcstArrivalsSink

          liveBaseArrivalsSourceSync ~> liveBaseBroadcast
          liveBaseBroadcast ~> liveBaseStatusSink
          liveBaseBroadcast
            .map {
              case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)
              case _ => FeedArrivals(List())
            } ~> liveBaseArrivalsSink

          liveArrivalsSourceSync ~> liveBroadcast
          liveBroadcast ~> liveStatusSink
          liveBroadcast
            .map {
              case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)
              case _ => FeedArrivals(List())
            } ~> arrivalsKillSwitchSync ~> liveArrivalsSink

          actualDesksAndWaitTimesSourceSync.out.map(_.asContainer) ~> deskStatsSink

          // @formatter:on

          ClosedShape
    }

    RunnableGraph
      .fromGraph(graph)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(RunnableCrunch.getClass.getName))
  }
}
