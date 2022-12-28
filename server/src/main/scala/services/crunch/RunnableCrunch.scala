package services.crunch

import actors.acking.AckingReceiver._
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.chroma.ArrivalsDiffingStage
import drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
import services.graphstages._
import services.metrics.Metrics
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.redlist.RedListUpdateCommand
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[FR, MS, SS, SFP, SMM, SAD, RL](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                           forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                           liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                           liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                           manifestsLiveSource: Source[ManifestsFeedResponse, MS],
                                           actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                                           redListUpdatesSource: Source[List[RedListUpdateCommand], RL],
                                           addTouchdownPredictions: ArrivalsDiff => Future[ArrivalsDiff],
                                           setPcpTimes: ArrivalsDiff => Future[ArrivalsDiff],

                                           arrivalsGraphStage: ArrivalsGraphStage,

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
                                           aggregatedArrivalsStateActor: ActorRef,

                                           forecastMaxMillis: () => MillisSinceEpoch
                                          )
                                          (implicit ec: ExecutionContext): RunnableGraph[(FR, FR, FR, FR, MS, SAD, RL, UniqueKillSwitch, UniqueKillSwitch)] = {

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
      redListUpdatesSource.async,
      arrivalsKillSwitch,
      manifestsLiveKillSwitch,
    )((_, _, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          forecastBaseArrivalsSourceSync,
          forecastArrivalsSourceSync,
          liveBaseArrivalsSourceSync,
          liveArrivalsSourceSync,
          manifestsLiveSourceSync,
          actualDesksAndWaitTimesSourceSync,
          redListUpdatesSourceAsync,
          arrivalsKillSwitchSync,
          manifestsLiveKillSwitchSync,
        ) =>
          def ackingActorSink(actorRef: ActorRef): SinkShape[Any] =
            builder.add(Sink.actorRefWithAck(actorRef, StreamInitialized, Ack, StreamCompleted, StreamFailure).async)

          def simpleActorSink(actorRef: ActorRef): SinkShape[Any] =
            builder.add(Sink.actorRef(actorRef, StreamCompleted).async)

          val arrivals = builder.add(arrivalsGraphStage)
          val deskStatsSink = ackingActorSink(portStateActor)

          val fcstArrivalsDiffing = builder.add(forecastArrivalsDiffStage)
          val liveBaseArrivalsDiffing = builder.add(liveBaseArrivalsDiffStage)
          val liveArrivalsDiffing = builder.add(liveArrivalsDiffStage)

          val forecastBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val forecastArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))

          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](2))

          val baseArrivalsSink = simpleActorSink(forecastBaseArrivalsActor)
          val fcstArrivalsSink = simpleActorSink(forecastArrivalsActor)
          val liveBaseArrivalsSink = simpleActorSink(liveBaseArrivalsActor)
          val liveArrivalsSink = simpleActorSink(liveArrivalsActor)
          val manifestsSink = simpleActorSink(manifestsActor)
          val flightsSink = ackingActorSink(portStateActor)
          val aggregatedArrivalsSink = simpleActorSink(aggregatedArrivalsStateActor)

          // @formatter:off
          forecastBaseArrivalsSourceSync.out.map {
            case ArrivalsFeedSuccess(Flights(as), ca) =>
              val maxScheduledMillis = forecastMaxMillis()
              ArrivalsFeedSuccess(Flights(as.filter(_.Scheduled < maxScheduledMillis)), ca)
            case failure => failure
          } ~> forecastBaseArrivalsFanOut

          forecastBaseArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) =>
              Metrics.successCounter("forecastBase.arrival", as.size)
              as.toList
            }
            .mapAsync(1)(applyPaxDeltas) ~> arrivals.in0
          forecastBaseArrivalsFanOut ~> baseArrivalsSink

          forecastArrivalsSourceSync ~> fcstArrivalsDiffing ~> forecastArrivalsFanOut

          forecastArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) if as.nonEmpty =>
              Metrics.successCounter("forecast.arrival", as.size)
              as.toList
            } ~> arrivals.in1
          forecastArrivalsFanOut ~> fcstArrivalsSink

          liveBaseArrivalsSourceSync ~> liveBaseArrivalsDiffing ~> liveBaseArrivalsFanOut
          liveBaseArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) if as.nonEmpty =>
              Metrics.successCounter("liveBase.arrival", as.size)
              as.toList
            } ~> arrivals.in2
          liveBaseArrivalsFanOut ~> liveBaseArrivalsSink

          liveArrivalsSourceSync ~> arrivalsKillSwitchSync ~> liveArrivalsDiffing ~> liveArrivalsFanOut
          liveArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) =>
              Metrics.successCounter("live.arrival", as.size)
              as.toList
            } ~> arrivals.in3

          redListUpdatesSourceAsync ~> arrivals.in4

          liveArrivalsFanOut ~> liveArrivalsSink

          manifestsLiveSourceSync.out.collect {
            case ManifestsFeedSuccess(manifests, createdAt) =>
              Metrics.successCounter("manifestsLive.arrival", manifests.length)
              ManifestsFeedSuccess(manifests, createdAt)
          } ~> manifestsLiveKillSwitchSync ~> manifestsSink

          arrivals.out
            .mapAsync(1) { diff =>
              if (diff.toUpdate.nonEmpty) {
                val startMillis = SDate.now().millisSinceEpoch
                val withoutPredictions = diff.toUpdate.count(_._2.PredictedTouchdown.isEmpty)
                addTouchdownPredictions(diff).map { diffWithPredictions =>
                  val predictionsAdded = withoutPredictions - diff.toUpdate.count(_._2.PredictedTouchdown.isEmpty)
                  val millisTaken = SDate.now().millisSinceEpoch - startMillis
                  log.info(s"Touchdown prediction lookups finished for $withoutPredictions arrivals. $predictionsAdded new predictions added. Took ${millisTaken}ms")
                  diffWithPredictions
                }
              } else Future.successful(diff)
            }
            .mapAsync(1) { diff =>
              if (diff.toUpdate.nonEmpty) setPcpTimes(diff) else Future.successful(diff)
            } ~> arrivalsFanOut
          arrivalsFanOut ~> flightsSink
          arrivalsFanOut ~> aggregatedArrivalsSink

          actualDesksAndWaitTimesSourceSync.out.map(_.asContainer) ~> deskStatsSink

          // @formatter:on

          ClosedShape
    }

    RunnableGraph
      .fromGraph(graph)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(RunnableCrunch.getClass.getName))
  }
}
