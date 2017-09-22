package services.graphstages

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
                cruncher: CrunchGraphStage,
                crunchStateActor: ActorRef): RunnableGraph[(M, M, NotUsed, NotUsed)] = {
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(flightsSource, voyageManifestsSource, cruncher, crunchSink)((_, _, _, _)) { implicit builder =>
      (fs, ms, cr, cs) =>
        fs ~> cr.in0
        ms ~> cr.in1
        cr.out ~> cs

        ClosedShape
    })
  }
}
