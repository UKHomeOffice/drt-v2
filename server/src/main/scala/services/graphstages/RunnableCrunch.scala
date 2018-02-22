package services.graphstages

import akka.actor.ActorRef
import akka.stream.ClosedShape
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.FlightsApi.Flights
import drt.shared.{ActualDeskStats, ApiSplits, Arrival, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SA, SVM, SS, SFP, SMM, SAD](
                                         baseArrivalsSource: Source[Flights, SA],
                                         fcstArrivalsSource: Source[Flights, SA],
                                         liveArrivalsSource: Source[Flights, SA],
                                         manifestsSource: Source[VoyageManifests, SVM],
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

    val graph = GraphDSL.create(
      baseArrivalsSource.async,
      fcstArrivalsSource.async,
      liveArrivalsSource.async,
      manifestsSource.async,
      shiftsSource.async,
      fixedPointsSource.async,
      staffMovementsSource.async,
      actualDesksAndWaitTimesSource.async
    )((_, _, _, _, _, _, _, _)) { implicit builder =>
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

        val fanOutArrivalsDiff = builder.add(Broadcast[ArrivalsDiff](3).async)
        val fanOutShifts = builder.add(Broadcast[String](2).async)
        val fanOutFixedPoints = builder.add(Broadcast[String](2).async)
        val fanOutStaffMovements = builder.add(Broadcast[Seq[StaffMovement]](2).async)
        val fanOutManifests = builder.add(Broadcast[VoyageManifests](2).async)
        val fanOutSplitsPredictions = builder.add(Broadcast[Seq[(Arrival, Option[ApiSplits])]](2).async)

        baseArrivalsSourceAsync ~> arrivalsStageAsync.in0
        fcstArrivalsSourceAsync ~> arrivalsStageAsync.in1
        liveArrivalsSourceAsync ~> arrivalsStageAsync.in2

        arrivalsStageAsync.out ~> fanOutArrivalsDiff ~> liveCrunchStageAsync.in0
                                  fanOutArrivalsDiff ~> fcstCrunchStageAsync.in0
                                  fanOutArrivalsDiff.map(_.toUpdate.toList) ~> splitsPredictorStageAsync

        splitsPredictorStageAsync.out ~> fanOutSplitsPredictions ~> liveCrunchStageAsync.in2
                                         fanOutSplitsPredictions ~> fcstCrunchStageAsync.in2

        manifestsSourceAsync ~> fanOutManifests ~> liveCrunchStageAsync.in1
                           fanOutManifests ~> fcstCrunchStageAsync.in1

        shiftsSourceAsync.out ~> fanOutShifts ~> liveStaffingStageAsync.in1
                            fanOutShifts ~> fcstStaffingStageAsync.in1

        fixedPointsSourceAsync.out ~> fanOutFixedPoints ~> liveStaffingStageAsync.in2
                                 fanOutFixedPoints ~> fcstStaffingStageAsync.in2

        staffMovementsSourceAsync.out ~> fanOutStaffMovements ~> liveStaffingStageAsync.in3
                                    fanOutStaffMovements ~> fcstStaffingStageAsync.in3

        liveCrunchStageAsync.out ~> liveStaffingStageAsync.in0

        desksAndWaitTimesSourceAsync.out ~> actualDesksStageAsync.in1
        liveStaffingStageAsync.out ~> actualDesksStageAsync.in0

        actualDesksStageAsync.out ~> liveCrunchOutAsync

        fcstCrunchStageAsync.out ~> fcstStaffingStageAsync.in0
        fcstStaffingStageAsync.out ~> fcstCrunchOutAsync

        ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
