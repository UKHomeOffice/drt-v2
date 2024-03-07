package services.crunch

import akka.actor.ActorRef
import akka.pattern.StatusReply.Ack
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.chroma.ArrivalsDiffingStage
import drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
import services.graphstages.ArrivalsGraphStage
import services.metrics.Metrics
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff}
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply[FR, MS, SAD, RL](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                             forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],
                             liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                             liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                             manifestsLiveSource: Source[ManifestsFeedResponse, MS],
                             actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                             //                             flushArrivalsSource: Source[Boolean, RL],
                             //                             addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff],
                             //                             setPcpTimes: ArrivalsDiff => Future[ArrivalsDiff],

                             //                             arrivalsGraphStage: ArrivalsGraphStage,

                             forecastArrivalsDiffStage: ArrivalsDiffingStage,
                             liveBaseArrivalsDiffStage: ArrivalsDiffingStage,
                             liveArrivalsDiffStage: ArrivalsDiffingStage,

                             forecastBaseArrivalsActor: ActorRef,
                             forecastArrivalsActor: ActorRef,
                             liveBaseArrivalsActor: ActorRef,
                             liveArrivalsActor: ActorRef,
                             applyPaxDeltas: List[Arrival] => Future[List[Arrival]],

                             manifestsActor: ActorRef,

                             portStateActor: ActorRef,
                             //                             aggregatedArrivalsStateActor: ActorRef,

                             forecastMaxMillis: () => MillisSinceEpoch
                            )
                            (implicit ec: ExecutionContext): RunnableGraph[(FR, FR, FR, FR, MS, SAD, UniqueKillSwitch, UniqueKillSwitch)] = {

    val arrivalsKillSwitch = KillSwitches.single[ArrivalsFeedResponse]
    val manifestsLiveKillSwitch = KillSwitches.single[ManifestsFeedResponse]

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.createGraph(
      forecastBaseArrivalsSource,
      forecastArrivalsSource,
      liveBaseArrivalsSource,
      liveArrivalsSource,
      manifestsLiveSource,
      actualDesksAndWaitTimesSource,
      //      flushArrivalsSource.async,
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

          val fcstArrivalsDiffing = builder.add(forecastArrivalsDiffStage)
          val liveBaseArrivalsDiffing = builder.add(liveBaseArrivalsDiffStage)
          val liveArrivalsDiffing = builder.add(liveArrivalsDiffStage)

          val baseArrivalsSink = simpleActorSink(forecastBaseArrivalsActor)
          val fcstArrivalsSink = simpleActorSink(forecastArrivalsActor)
          val liveBaseArrivalsSink = simpleActorSink(liveBaseArrivalsActor)
          val liveArrivalsSink = simpleActorSink(liveArrivalsActor)
          val manifestsSink = simpleActorSink(manifestsActor)

          // @formatter:off
          forecastBaseArrivalsSourceSync.out.map {
            case ArrivalsFeedSuccess(Flights(as), ca) =>
              val maxScheduledMillis = forecastMaxMillis()
              ArrivalsFeedSuccess(Flights(as.filter(_.Scheduled < maxScheduledMillis)), ca)
            case failure => failure
          }.mapAsync(1) {
            case ArrivalsFeedSuccess(Flights(as), _) =>
              applyPaxDeltas(as.toList)
                .map(updated => ArrivalsFeedSuccess(Flights(updated), SDate.now()))
            case failure => Future.successful(failure)
          } ~> baseArrivalsSink

          forecastArrivalsSourceSync ~> fcstArrivalsDiffing ~> fcstArrivalsSink

          liveBaseArrivalsSourceSync ~> liveBaseArrivalsDiffing ~> liveBaseArrivalsSink

          liveArrivalsSourceSync ~> arrivalsKillSwitchSync ~> liveArrivalsDiffing ~> liveArrivalsSink

          manifestsLiveSourceSync.out.collect {
            case ManifestsFeedSuccess(manifests, createdAt) =>
              Metrics.successCounter("manifestsLive.arrival", manifests.length)
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

  private def addPredictionsToDiff(addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff], date: UtcDate, diff: ArrivalsDiff)
                                  (implicit executionContext: ExecutionContext): Future[ArrivalsDiff] =
    if (diff.toUpdate.nonEmpty) {
      log.info(f"Looking up arrival predictions for ${diff.toUpdate.size} arrivals on ${date.day}%02d/${date.month}%02d/${date.year}")
      val startMillis = SDate.now().millisSinceEpoch
      val withoutPredictions = diff.toUpdate.count(_._2.predictedTouchdown.isEmpty)
      addArrivalPredictions(diff).map { diffWithPredictions =>
        val millisTaken = SDate.now().millisSinceEpoch - startMillis
        log.info(s"Arrival prediction lookups finished for $withoutPredictions arrivals. Took ${millisTaken}ms")
        diffWithPredictions
      }
    } else Future.successful(diff)
}
