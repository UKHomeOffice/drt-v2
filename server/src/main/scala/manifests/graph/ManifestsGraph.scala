package manifests.graph

import actors.AckingReceiver._
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.stage.GraphStage
import drt.shared.{Arrival, ArrivalKey}
import manifests.actors.RegisteredArrivals

object ManifestsGraph {
  def apply(arrivalsSource: Source[List[Arrival], SourceQueueWithComplete[List[Arrival]]],
            batchStage: GraphStage[FanOutShape2[List[Arrival], List[ArrivalKey],RegisteredArrivals]],
            lookupStage: GraphStage[FlowShape[List[ArrivalKey], ManifestTries]],
            manifestsSinkActor: ActorRef,
            registeredArrivalsActor: ActorRef
           ): RunnableGraph[SourceQueueWithComplete[List[Arrival]]] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(arrivalsSource.async) {
      implicit builder =>
        arrivals =>
          val batchRequests = builder.add(batchStage.async)
          val manifestLookup = builder.add(lookupStage.async)
          val manifestsSink = builder.add(Sink.actorRefWithAck(manifestsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))
          val registeredArrivalsSink = builder.add(Sink.actorRef(registeredArrivalsActor, "completed"))

          arrivals ~> batchRequests.in

          batchRequests.out0 ~> manifestLookup ~> manifestsSink
          batchRequests.out1 ~> registeredArrivalsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
