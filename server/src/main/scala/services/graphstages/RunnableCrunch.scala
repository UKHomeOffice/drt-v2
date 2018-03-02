package services.graphstages

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests

import scala.language.postfixOps


object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SA, SVM, SS, SFP, SMM, SAD](
                                         baseArrivalsSource: Source[Flights, SA],
                                         fcstArrivalsSource: Source[Flights, SA],
                                         liveArrivalsSource: Source[Flights, SA],
                                         manifestsActor: ActorRef,
                                         manifestsSource: Source[DqManifests, SVM],
                                         splitsPredictorStage: SplitsPredictorBase = new DummySplitsPredictor(),
                                         shiftsSource: Source[String, SS],
                                         fixedPointsSource: Source[String, SFP],
                                         staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                         actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                                         arrivalsShape: Graph[FanInShape3[Flights, Flights, Flights, ArrivalsDiff], NotUsed],
                                         liveCrunchShape: Graph[FanInShape7[ArrivalsDiff, VoyageManifests, Seq[(Arrival, Option[ApiSplits])], String, String, Seq[StaffMovement], ActualDeskStats, CrunchApi.PortState], NotUsed],
                                         fcstCrunchShape: Graph[FanInShape6[ArrivalsDiff, VoyageManifests, Seq[(Arrival, Option[ApiSplits])], String, String, Seq[StaffMovement], CrunchApi.PortState], NotUsed],
                                         fcstCrunchStateActor: ActorRef,
                                         liveCrunchStateActor: ActorRef
                                       ): RunnableGraph[(SA, SA, SA, SVM, SS, SFP, SMM, SAD)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val liveCrunchSink = Sink.actorRef(liveCrunchStateActor, "completed")
    val fcstCrunchSink = Sink.actorRef(fcstCrunchStateActor, "completed")

    val manifestsSink = Sink.actorRef(manifestsActor, "completed")

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
          val arrivals = builder.add(arrivalsShape.async)
          val liveCrunch = builder.add(liveCrunchShape.async)
          val fcstCrunch = builder.add(fcstCrunchShape.async)
          val livePortStateSink = builder.add(liveCrunchSink.async)

          val fcstPortStateSink = builder.add(fcstCrunchSink.async)
          val splitsPredictorStageAsync = builder.add(splitsPredictorStage.async)

          val manifestsOut = builder.add(manifestsSink.async)

          val fanOutArrivalsDiff = builder.add(Broadcast[ArrivalsDiff](3).async)
          val fanOutShifts = builder.add(Broadcast[String](2).async)
          val fanOutFixedPoints = builder.add(Broadcast[String](2).async)
          val fanOutStaffMovements = builder.add(Broadcast[Seq[StaffMovement]](2).async)
          val fanOutManifests = builder.add(Broadcast[DqManifests](3).async)
          val fanOutSplitsPredictions = builder.add(Broadcast[Seq[(Arrival, Option[ApiSplits])]](2).async)

          baseArrivalsSourceAsync ~> arrivals.in0
          fcstArrivalsSourceAsync ~> arrivals.in1
          liveArrivalsSourceAsync ~> arrivals.in2

          arrivals.out ~> fanOutArrivalsDiff

          fanOutArrivalsDiff.out(0).map(_.toUpdate.toList) ~> splitsPredictorStageAsync
          fanOutArrivalsDiff.out(1).conflate[ArrivalsDiff] { case (ad1, ad2) => Crunch.mergeArrivalsDiffs(ad1, ad2) } ~> liveCrunch.in0
          fanOutArrivalsDiff.out(2).conflate[ArrivalsDiff] { case (ad1, ad2) => Crunch.mergeArrivalsDiffs(ad1, ad2) } ~> fcstCrunch.in0

          splitsPredictorStageAsync.out ~> fanOutSplitsPredictions

          fanOutSplitsPredictions.out(0).conflate[Seq[(Arrival, Option[ApiSplits])]] {
            case (as1, as2) => Crunch.combineArrivalsWithMaybeSplits(as1, as2)
          } ~> liveCrunch.in2

          fanOutSplitsPredictions.out(1).conflate[Seq[(Arrival, Option[ApiSplits])]] {
            case (as1, as2) => Crunch.combineArrivalsWithMaybeSplits(as1, as2)
          } ~> fcstCrunch.in2

          manifestsSourceAsync.out ~> fanOutManifests

          fanOutManifests.out(0).map(dqm => VoyageManifests(dqm.manifests)).conflate[VoyageManifests] {
            case (VoyageManifests(m1), VoyageManifests(m2)) => VoyageManifests(m1 ++ m2)
          } ~> liveCrunch.in1

          fanOutManifests.out(1).map(dqm => VoyageManifests(dqm.manifests)).conflate[VoyageManifests] {
            case (VoyageManifests(m1), VoyageManifests(m2)) => VoyageManifests(m1 ++ m2)
          } ~> fcstCrunch.in1

          fanOutManifests.out(2) ~> manifestsOut

          shiftsSourceAsync.out ~> fanOutShifts
          fanOutShifts.out(0).conflate[String] { case (_, s2) => s2 } ~> liveCrunch.in3
          fanOutShifts.out(1).conflate[String] { case (_, s2) => s2 } ~> fcstCrunch.in3

          fixedPointsSourceAsync.out ~> fanOutFixedPoints
          fanOutFixedPoints.out(0).conflate[String] { case (_, fp2) => fp2 } ~> liveCrunch.in4
          fanOutFixedPoints.out(1).conflate[String] { case (_, fp2) => fp2 } ~> fcstCrunch.in4

          staffMovementsSourceAsync.out ~> fanOutStaffMovements
          fanOutStaffMovements.out(0).conflate[Seq[StaffMovement]] { case (_, sm2) => sm2 } ~> liveCrunch.in5
          fanOutStaffMovements.out(1).conflate[Seq[StaffMovement]] { case (_, sm2) => sm2 } ~> fcstCrunch.in5

          desksAndWaitTimesSourceAsync.out ~> liveCrunch.in6

          liveCrunch.out ~> livePortStateSink
          fcstCrunch.out ~> fcstPortStateSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
