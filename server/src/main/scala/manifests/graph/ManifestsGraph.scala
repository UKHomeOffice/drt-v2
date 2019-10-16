package manifests.graph

import actors.ManifestTries
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.stage.GraphStage
import drt.shared.{Arrival, ArrivalKey}
import manifests.ManifestLookupLike
import manifests.actors.RegisteredArrivals
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

object ManifestsGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(arrivalsSource: Source[List[Arrival], SourceQueueWithComplete[List[Arrival]]],
            batchStage: GraphStage[FanOutShape2[List[Arrival], List[ArrivalKey], RegisteredArrivals]],
            manifestsSinkActor: ActorRef,
            registeredArrivalsActor: ActorRef,
            portCode: String,
            manifestLookup: ManifestLookupLike
           ): RunnableGraph[SourceQueueWithComplete[List[Arrival]]] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(arrivalsSource.async) {
      implicit builder =>
        arrivals =>
          val batchRequests = builder.add(batchStage.async)
          val manifestsSink = builder.add(Sink.actorRefWithAck(manifestsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))
          val registeredArrivalsSink = builder.add(Sink.actorRef(registeredArrivalsActor, "completed"))

          arrivals ~> batchRequests.in

          batchRequests.out0
            .flatMapConcat(arrivals => Source(arrivals))
            .mapAsync(1) { a =>
              manifestLookup.maybeBestAvailableManifest(portCode, a.origin, a.voyageNumber, SDate(a.scheduled))
            }
            .collect { case (_, bam) if bam.isDefined => bam }
            .conflateWithSeed(List[Option[BestAvailableManifest]](_)) {
              case (acc, next) => next :: acc
            }
            .map(ManifestTries(_)) ~> manifestsSink

          batchRequests.out1 ~> registeredArrivalsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
