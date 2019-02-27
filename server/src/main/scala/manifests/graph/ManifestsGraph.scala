package manifests.graph

import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.stage.GraphStage
import drt.shared.Arrival

object ManifestsGraph {
  def apply(arrivalsSource: Source[List[Arrival], SourceQueueWithComplete[List[Arrival]]],
            requestPrioritisationStage: GraphStage[FlowShape[List[Arrival], List[SimpleArrival]]],
            requestsExecutorStage: GraphStage[FlowShape[List[SimpleArrival], ManifestTries]],
            manifestsSinkActor: ActorRef): RunnableGraph[SourceQueueWithComplete[List[Arrival]]] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(arrivalsSource.async) {
      implicit builder =>
        arrivals =>
          val batchRequests = builder.add(requestPrioritisationStage.async)
          val requestsExecutor = builder.add(requestsExecutorStage.async)
          val manifestsSink = builder.add(Sink.actorRef(manifestsSinkActor, "completed"))

          arrivals ~> batchRequests ~> requestsExecutor ~> manifestsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
