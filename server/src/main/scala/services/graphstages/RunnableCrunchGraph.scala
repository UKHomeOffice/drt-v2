package services.graphstages

import actors.StaffMovements
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.ClosedShape
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.FlightsApi.Flights
import passengersplits.parsing.VoyageManifestParser.VoyageManifests


object RunnableCrunchGraph {

  def apply[M](
                flightsSource: Source[Flights, M],
                voyageManifestsSource: Source[VoyageManifests, M],
                shiftsSource: Source[String, M],
                fixedPointsSource: Source[String, M],
                staffMovementsSource: Source[StaffMovements, M],
                staffingStage: StaffingStage,
                cruncher: CrunchGraphStage,
                crunchStateActor: ActorRef): RunnableGraph[(M, M, M, M, M, NotUsed, NotUsed, NotUsed)] = {
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(
      flightsSource,
      voyageManifestsSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource,
      cruncher,
      staffingStage,
      crunchSink)((_, _, _, _, _, _, _, _)) { implicit builder =>
      (flights, manifests, shifts, fixedpoints, movements, crunch, staffing, crunchSink) =>
        flights ~> crunch.in0
        manifests ~> crunch.in1

        crunch.out ~> staffing.in0
        shifts ~> staffing.in1
        fixedpoints ~> staffing.in2
        movements ~> staffing.in3

        staffing.out ~> crunchSink

        ClosedShape
    })
  }
}
