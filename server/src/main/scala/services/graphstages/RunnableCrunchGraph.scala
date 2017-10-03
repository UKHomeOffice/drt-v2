package services.graphstages

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.ClosedShape
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.FlightsApi.Flights
import drt.shared.{ActualDeskStats, StaffMovement}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests


object RunnableCrunchGraph {

  def apply[FS, M, SM, SMM, SAD](
                flightsSource: Source[Flights, FS],
                voyageManifestsSource: Source[VoyageManifests, M],
                shiftsSource: Source[String, SM],
                fixedPointsSource: Source[String, SM],
                staffMovementsSource: Source[Seq[StaffMovement], SMM],
                actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                staffingStage: StaffingStage,
                cruncher: CrunchGraphStage,
                actualDesks: ActualDesksAndWaitTimesGraphStage,
                crunchStateActor: ActorRef): RunnableGraph[(FS, M, SM, SM, SMM, SAD, NotUsed, NotUsed, NotUsed, NotUsed)] = {
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(
      flightsSource,
      voyageManifestsSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource,
      actualDesksAndWaitTimesSource,
      cruncher,
      staffingStage,
      actualDesks,
      crunchSink)((_, _, _, _, _, _, _, _, _, _)) { implicit builder =>
      (flights, manifests, shifts, fixedpoints, movements, actuals, crunch, staffing, actualDesks, crunchSink) =>
        flights ~> crunch.in0
        manifests ~> crunch.in1

        crunch.out ~> staffing.in0
        shifts ~> staffing.in1
        fixedpoints ~> staffing.in2
        movements ~> staffing.in3

        staffing.out ~> actualDesks.in0
        actuals ~> actualDesks.in1

        actualDesks.out ~> crunchSink

        ClosedShape
    })
  }
}
