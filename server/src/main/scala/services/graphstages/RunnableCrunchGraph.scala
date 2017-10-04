package services.graphstages

import akka.actor.ActorRef
import akka.stream.ClosedShape
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.FlightsApi.Flights
import drt.shared.{ActualDeskStats, StaffMovement}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests


object RunnableCrunchGraph {
  def apply[SA, SVM, SS, SFP, SMM, SAD](
                                  baseArrivalsSource: Source[Flights, SA],
                                  liveArrivalsSource: Source[Flights, SA],
                                  voyageManifestsSource: Source[VoyageManifests, SVM],
                                  shiftsSource: Source[String, SS],
                                  fixedPointsSource: Source[String, SFP],
                                  staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                  actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                                  staffingStage: StaffingStage,
                                  arrivalsStage: ArrivalsGraphStage,
                                  cruncher: CrunchGraphStage,
                                  actualDesksStage: ActualDesksAndWaitTimesGraphStage,
                                  crunchStateActor: ActorRef): RunnableGraph[(SA, SA, SVM, SS, SFP, SMM, SAD)] = {
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(
      baseArrivalsSource,
      liveArrivalsSource,
      voyageManifestsSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource,
      actualDesksAndWaitTimesSource)((_, _, _, _, _, _, _)) { implicit builder =>
      (baseArrivals, liveArrivals, manifests, shifts, fixedPoints, movements, actuals) =>
        val arrivals = builder.add(arrivalsStage)
        val crunch = builder.add(cruncher)
        val staffing = builder.add(staffingStage)
        val addActuals = builder.add(actualDesksStage)

        baseArrivals ~> arrivals.in0
        liveArrivals ~> arrivals.in1

        arrivals.out ~> crunch.in0
        manifests ~> crunch.in1

        crunch.out ~> staffing.in0
        shifts ~> staffing.in1
        fixedPoints ~> staffing.in2
        movements ~> staffing.in3

        staffing.out ~> addActuals.in0
        actuals ~> addActuals.in1

        addActuals.out ~> crunchSink

        ClosedShape
    })
  }
}
