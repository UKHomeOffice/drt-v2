package manifests.graph

import actors.acking.AckingReceiver.StreamCompleted
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.stage.GraphStage
import drt.shared.{Arrival, ArrivalKey}
import manifests.ManifestLookupLike
import manifests.actors.RegisteredArrivals
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

object ManifestsGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(arrivalsSource: Source[List[Arrival], NotUsed],
            batchStage: GraphStage[FanOutShape2[List[Arrival], List[ArrivalKey], RegisteredArrivals]],
            manifestsSink: Sink[List[BestAvailableManifest], NotUsed],
            registeredArrivalsActor: ActorRef,
            portCode: String,
            manifestLookup: ManifestLookupLike
           ): RunnableGraph[UniqueKillSwitch] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val killSwitch = KillSwitches.single[List[Arrival]]

    val graph = GraphDSL.create(killSwitch.async) {
      implicit builder =>
        killSwitchAsync =>
          val arrivalsAsync = builder.add(arrivalsSource.async)
          val batchRequestsAsync = builder.add(batchStage.async)
          val registeredArrivalsSink = builder.add(Sink.actorRef(registeredArrivalsActor, StreamCompleted))

          arrivalsAsync.out.conflate[List[Arrival]] {
            case (acc, incoming) => acc ++ incoming
          } ~> killSwitchAsync ~> batchRequestsAsync.in

          batchRequestsAsync.out0
            .flatMapConcat(arrivals => Source(arrivals))
            .mapAsync(1) { a =>
              manifestLookup.maybeBestAvailableManifest(portCode, a.origin, a.voyageNumber, SDate(a.scheduled))
            }
            .collect { case (_, Some(bam)) => bam }
            .conflateWithSeed(List[BestAvailableManifest](_)) {
              case (acc, next) =>
                log.info(s"${acc.length + 1} conflated BestAvailableManifests")
                next :: acc
            } ~> manifestsSink

          batchRequestsAsync.out1 ~> registeredArrivalsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
