package services.crunch

import actors.acking.AckingReceiver._
import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.chroma.ArrivalsDiffingStage
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.Flights
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import server.feeds._
import services.StreamSupervision
import services.graphstages._

import scala.concurrent.Future

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[FR, MS, SS, SFP, SMM, SAD](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       manifestsLiveSource: Source[ManifestsFeedResponse, MS],
                                       shiftsSource: Source[ShiftAssignments, SS],
                                       fixedPointsSource: Source[FixedPointAssignments, SFP],
                                       staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                       actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],

                                       arrivalsGraphStage: ArrivalsGraphStage,
                                       staffGraphStage: StaffGraphStage,

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
                                       deploymentRequestActor: ActorRef,

                                       forecastMaxMillis: () => MillisSinceEpoch
                                      ): RunnableGraph[(FR, FR, FR, FR, MS, SS, SFP, SMM, SAD, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch)] = {

    val arrivalsKillSwitch = KillSwitches.single[ArrivalsFeedResponse]
    val manifestsLiveKillSwitch = KillSwitches.single[ManifestsFeedResponse]
    val shiftsKillSwitch = KillSwitches.single[ShiftAssignments]
    val fixedPointsKillSwitch = KillSwitches.single[FixedPointAssignments]
    val movementsKillSwitch = KillSwitches.single[Seq[StaffMovement]]

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      forecastBaseArrivalsSource,
      forecastArrivalsSource,
      liveBaseArrivalsSource,
      liveArrivalsSource,
      manifestsLiveSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource,
      actualDesksAndWaitTimesSource,
      arrivalsKillSwitch,
      manifestsLiveKillSwitch,
      shiftsKillSwitch,
      fixedPointsKillSwitch,
      movementsKillSwitch
      )((_, _, _, _, _, _, _, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          forecastBaseArrivalsSourceSync,
          forecastArrivalsSourceSync,
          liveBaseArrivalsSourceSync,
          liveArrivalsSourceSync,
          manifestsLiveSourceSync,
          shiftsSourceAsync,
          fixedPointsSourceAsync,
          staffMovementsSourceAsync,
          actualDesksAndWaitTimesSourceSync,
          arrivalsKillSwitchSync,
          manifestsLiveKillSwitchSync,
          shiftsKillSwitchSync,
          fixedPointsKillSwitchSync,
          movementsKillSwitchSync
        ) =>
          def ackingActorSink(actorRef: ActorRef): SinkShape[Any] =
            builder.add(Sink.actorRefWithAck(actorRef, StreamInitialized, Ack, StreamCompleted, StreamFailure).async)

          def simpleActorSink(actorRef: ActorRef): SinkShape[Any] =
            builder.add(Sink.actorRef(actorRef, StreamCompleted).async)

          val arrivals = builder.add(arrivalsGraphStage)
          val staff = builder.add(staffGraphStage)
          val deploymentRequestSink = builder.add(Sink.actorRef(deploymentRequestActor, StreamCompleted))
          val deskStatsSink = ackingActorSink(portStateActor)

          val staffSink = ackingActorSink(portStateActor)
          val fcstArrivalsDiffing = builder.add(forecastArrivalsDiffStage)
          val liveBaseArrivalsDiffing = builder.add(liveBaseArrivalsDiffStage)
          val liveArrivalsDiffing = builder.add(liveArrivalsDiffStage)

          val forecastBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val forecastArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))

          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](2))
          val staffFanOut = builder.add(Broadcast[StaffMinutes](2))

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
            .collect { case ArrivalsFeedSuccess(Flights(as), _) => as.toList }
            .mapAsync(1)(applyPaxDeltas) ~> arrivals.in0
          forecastBaseArrivalsFanOut ~> baseArrivalsSink

          forecastArrivalsSourceSync ~> fcstArrivalsDiffing ~> forecastArrivalsFanOut

          forecastArrivalsFanOut.collect { case ArrivalsFeedSuccess(Flights(as), _) if as.nonEmpty => as.toList } ~> arrivals.in1
          forecastArrivalsFanOut ~> fcstArrivalsSink

          liveBaseArrivalsSourceSync ~> liveBaseArrivalsDiffing ~> liveBaseArrivalsFanOut
          liveBaseArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) if as.nonEmpty => as.toList }
            .conflate[List[Arrival]] { case (acc, incoming) =>
                log.info(s"${acc.length + incoming.length} conflated live base arrivals")
                acc ++ incoming } ~> arrivals.in2
          liveBaseArrivalsFanOut ~> liveBaseArrivalsSink

          liveArrivalsSourceSync ~> arrivalsKillSwitchSync ~> liveArrivalsDiffing ~> liveArrivalsFanOut
          liveArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) => as.toList }
            .conflate[List[Arrival]] { case (acc, incoming) =>
                log.info(s"${acc.length + incoming.length} conflated live arrivals")
                acc ++ incoming } ~> arrivals.in3
          liveArrivalsFanOut ~> liveArrivalsSink

          manifestsLiveSourceSync ~> manifestsLiveKillSwitchSync ~> manifestsSink

          shiftsSourceAsync          ~> shiftsKillSwitchSync ~> staff.in0
          fixedPointsSourceAsync     ~> fixedPointsKillSwitchSync ~> staff.in1
          staffMovementsSourceAsync  ~> movementsKillSwitchSync ~> staff.in2

          arrivals.out ~> arrivalsFanOut
                          arrivalsFanOut ~> flightsSink
                          arrivalsFanOut ~> aggregatedArrivalsSink

          actualDesksAndWaitTimesSourceSync ~> deskStatsSink

          staff.out ~> staffFanOut ~> staffSink
                       staffFanOut.map(staffMinutes => UpdatedMillis(staffMinutes.millis)) ~> deploymentRequestSink

          // @formatter:on

          ClosedShape
    }

    RunnableGraph
      .fromGraph(graph)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(RunnableCrunch.getClass.getName))
  }
}
