package services.graphstages

import akka.actor.ActorRef
import akka.stream.{Attributes, ClosedShape, ThrottleMode}
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.{ActualDeskStats, ApiSplits, Arrival, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.ArrivalsState

import scala.language.postfixOps
import scala.concurrent.duration._

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SA, SVM, SS, SFP, SMM, SAD](
                                         baseArrivalsSource: Source[Flights, SA],
                                         fcstArrivalsSource: Source[Flights, SA],
                                         liveArrivalsSource: Source[Flights, SA],
                                         baseArrivalsActor: ActorRef,
                                         fcstArrivalsActor: ActorRef,
                                         liveArrivalsActor: ActorRef,
                                         manifestsActor: ActorRef,
                                         manifestsSource: Source[DqManifests, SVM],
                                         splitsPredictorStage: SplitsPredictorBase = new DummySplitsPredictor(),
                                         shiftsSource: Source[String, SS],
                                         fixedPointsSource: Source[String, SFP],
                                         staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                         actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                                         arrivalsStage: ArrivalsGraphStage,
                                         actualDesksStage: ActualDesksAndWaitTimesGraphStage,
                                         liveCrunchStage: CrunchGraphStage,
                                         liveStaffingStage: StaffingStage,
                                         liveCrunchStateActor: ActorRef,
                                         fcstCrunchStage: CrunchGraphStage,
                                         fcstStaffingStage: StaffingStage,
                                         fcstCrunchStateActor: ActorRef
                                       ): RunnableGraph[(SA, SA, SA, SVM, SS, SFP, SMM, SAD)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val liveCrunchSink = Sink.actorRef(liveCrunchStateActor, "completed")
    val fcstCrunchSink = Sink.actorRef(fcstCrunchStateActor, "completed")

    val baseArrivalsSink = Sink.actorRef(baseArrivalsActor, "completed")
    val fcstArrivalsSink = Sink.actorRef(fcstArrivalsActor, "completed")
    val liveArrivalsSink = Sink.actorRef(liveArrivalsActor, "completed")

    val manifestsSink = Sink.actorRef(manifestsActor, "completed")

    def combineArrivalsWithMaybeSplits(as1: Seq[(Arrival, Option[ApiSplits])], as2: Seq[(Arrival, Option[ApiSplits])]) = {
      val arrivalsWithMaybeSplitsById = as1
        .map {
          case (arrival, maybeSplits) => (arrival.uniqueId, (arrival, maybeSplits))
        }
        .toMap
      as2
        .foldLeft(arrivalsWithMaybeSplitsById) {
          case (soFar, (arrival, maybeNewSplits)) =>
            soFar.updated(arrival.uniqueId, (arrival, maybeNewSplits))
        }
        .map {
          case (_, arrivalWithMaybeSplits) => arrivalWithMaybeSplits
        }
        .toSeq
    }

    def combineArrivalsDiffs(ad1: ArrivalsDiff, ad2: ArrivalsDiff) = {
      val updatesById = ad1.toUpdate.toSeq.map(a => (a.uniqueId, a)).toMap
      val toUpdate = ad2
        .toUpdate
        .foldLeft(updatesById) {
          case (soFar, arrival) => soFar.updated(arrival.uniqueId, arrival)
        }
        .map {
          case (_, arrival) => arrival
        }
        .toSet
      val removals = ad1.toRemove ++ ad2.toRemove
      ArrivalsDiff(toRemove = removals, toUpdate = toUpdate)
    }

    val graph = GraphDSL.create(
      baseArrivalsSource.async,
      fcstArrivalsSource.async,
      liveArrivalsSource.async,
      manifestsSource.async,
      shiftsSource.async,
      fixedPointsSource.async,
      staffMovementsSource.async,
      actualDesksAndWaitTimesSource.async
    )((_, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          baseArrivalsSourceAsync,
          fcstArrivalsSourceAsync,
          liveArrivalsSourceAsync,
          manifestsSourceAsync,
          shiftsSourceAsync,
          fixedPointsSourceAsync,
          staffMovementsSourceAsync,
          desksAndWaitTimesSourceAsync
        ) =>
          val arrivalsStageAsync = builder.add(arrivalsStage.async)
          val liveCrunchStageAsync = builder.add(liveCrunchStage.async)
          val liveStaffingStageAsync = builder.add(liveStaffingStage.async)
          val liveCrunchOutAsync = builder.add(liveCrunchSink.async)
          val fcstCrunchStageAsync = builder.add(fcstCrunchStage.async)
          val fcstStaffingStageAsync = builder.add(fcstStaffingStage.async)
          val fcstCrunchOutAsync = builder.add(fcstCrunchSink.async)
          val actualDesksStageAsync = builder.add(actualDesksStage.async)
          val splitsPredictorStageAsync = builder.add(splitsPredictorStage.async)

          val baseArrivalsOut = builder.add(baseArrivalsSink.async)
          val fcstArrivalsOut = builder.add(fcstArrivalsSink.async)
          val liveArrivalsOut = builder.add(liveArrivalsSink.async)
          val manifestsOut = builder.add(manifestsSink.async)

          val fanOutArrivalsDiff = builder.add(Broadcast[ArrivalsDiff](3).async)
          val fanOutShifts = builder.add(Broadcast[String](2).async)
          val fanOutFixedPoints = builder.add(Broadcast[String](2).async)
          val fanOutStaffMovements = builder.add(Broadcast[Seq[StaffMovement]](2).async)
          val fanOutManifests = builder.add(Broadcast[DqManifests](3).async)
          val fanOutSplitsPredictions = builder.add(Broadcast[Seq[(Arrival, Option[ApiSplits])]](2).async)
          val fanOutBase = builder.add(Broadcast[Flights](2))
          val fanOutFcst = builder.add(Broadcast[Flights](2))
          val fanOutLive = builder.add(Broadcast[Flights](2))

          baseArrivalsSourceAsync ~> fanOutBase
          fanOutBase.out(0) ~> arrivalsStageAsync.in0
          fanOutBase.out(1).map(f => ArrivalsState(f.flights.map(x => (x.uniqueId, x)).toMap)) ~> baseArrivalsOut

          fcstArrivalsSourceAsync ~> fanOutFcst
          fanOutFcst.out(0) ~> arrivalsStageAsync.in1
          fanOutFcst.out(1).map(f => ArrivalsState(f.flights.map(x => (x.uniqueId, x)).toMap)) ~> fcstArrivalsOut

          liveArrivalsSourceAsync ~> fanOutLive
          fanOutLive.out(0) ~> arrivalsStageAsync.in2
          fanOutLive.out(1).map(f => ArrivalsState(f.flights.map(x => (x.uniqueId, x)).toMap)) ~> liveArrivalsOut

          arrivalsStageAsync.out ~> fanOutArrivalsDiff

          fanOutArrivalsDiff.map(_.toUpdate.toList) ~> splitsPredictorStageAsync
          fanOutArrivalsDiff.conflate[ArrivalsDiff] {
            case (ad1, ad2) => combineArrivalsDiffs(ad1, ad2)
          } ~> liveCrunchStageAsync.in0
          fanOutArrivalsDiff.conflate[ArrivalsDiff] {
            case (ad1, ad2) => combineArrivalsDiffs(ad1, ad2)
          } ~> fcstCrunchStageAsync.in0

          splitsPredictorStageAsync.out ~> fanOutSplitsPredictions

          fanOutSplitsPredictions.out(0).conflate[Seq[(Arrival, Option[ApiSplits])]] {
            case (as1, as2) => combineArrivalsWithMaybeSplits(as1, as2)
          } ~> liveCrunchStageAsync.in2

          fanOutSplitsPredictions.out(1).conflate[Seq[(Arrival, Option[ApiSplits])]] {
            case (as1, as2) => combineArrivalsWithMaybeSplits(as1, as2)
          } ~> fcstCrunchStageAsync.in2

          manifestsSourceAsync.out ~> fanOutManifests

          fanOutManifests.out(0).map(dqm => VoyageManifests(dqm.manifests)).conflate[VoyageManifests] {
            case (VoyageManifests(m1), VoyageManifests(m2)) => VoyageManifests(m1 ++ m2)
          } ~> liveCrunchStageAsync.in1

          fanOutManifests.out(1).map(dqm => VoyageManifests(dqm.manifests)).conflate[VoyageManifests] {
            case (VoyageManifests(m1), VoyageManifests(m2)) => VoyageManifests(m1 ++ m2)
          } ~> fcstCrunchStageAsync.in1

          fanOutManifests.out(2) ~> manifestsOut

          shiftsSourceAsync.out ~> fanOutShifts
          fanOutShifts.out(0).conflate[String] {
            case (_, s2) => s2
          } ~> liveStaffingStageAsync.in1
          fanOutShifts.out(1).conflate[String] {
            case (_, s2) => s2
          } ~> fcstStaffingStageAsync.in1

          fixedPointsSourceAsync.out ~> fanOutFixedPoints
          fanOutFixedPoints.out(0).conflate[String] {
            case (_, fp2) => fp2
          } ~> liveStaffingStageAsync.in2
          fanOutFixedPoints.out(1).conflate[String] {
            case (_, fp2) => fp2
          } ~> fcstStaffingStageAsync.in2

          staffMovementsSourceAsync.out ~> fanOutStaffMovements
          fanOutStaffMovements.out(0).conflate[Seq[StaffMovement]] {
            case (_, sm2) => sm2
          } ~> liveStaffingStageAsync.in3
          fanOutStaffMovements.out(1).conflate[Seq[StaffMovement]] {
            case (_, sm2) => sm2
          } ~> fcstStaffingStageAsync.in3

          liveCrunchStageAsync.out.conflate[PortState] {
            case (ps1, ps2) => Crunch.mergePortState(ps1, ps2)
          } ~> liveStaffingStageAsync.in0

          desksAndWaitTimesSourceAsync.out ~> actualDesksStageAsync.in1

          liveStaffingStageAsync.out.conflate[PortState] {
            case (ps1, ps2) => Crunch.mergePortState(ps1, ps2)
          } ~> actualDesksStageAsync.in0

          actualDesksStageAsync.out.conflate[PortState] {
            case (ps1, ps2) => Crunch.mergePortState(ps1, ps2)
          } ~> liveCrunchOutAsync

          fcstCrunchStageAsync.out.conflate[PortState] {
            case (ps1, ps2) => Crunch.mergePortState(ps1, ps2)
          } ~> fcstStaffingStageAsync.in0

          fcstStaffingStageAsync.out.conflate[PortState] {
            case (ps1, ps2) => Crunch.mergePortState(ps1, ps2)
          } ~> fcstCrunchOutAsync

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
