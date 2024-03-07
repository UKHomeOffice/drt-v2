package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.StatusReply
import akka.stream.scaladsl.GraphDSL.Implicits.port2flow
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink}
import akka.stream.{ClosedShape, KillSwitches, UniqueKillSwitch}
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}

object QueuedRequestProcessing {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def createGraph[A, B](processingRequestSource: SortedActorRefSource,
                        sinkActor: ActorRef,
                        processor: Flow[B, A, NotUsed],
                        graphName: String,
                       ): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    val deskRecsSink = Sink.actorRefWithAck(sinkActor, StreamInitialized, StatusReply.Ack, StreamCompleted, StreamFailure)
    val ks = KillSwitches.single[A]

    val graph = GraphDSL.create(processingRequestSource, ks)((_, _)) {
      implicit builder =>
        (requests, killSwitch) =>
          requests.out.collect {
            case cr: B =>
              log.info(s"[$graphName] Sending $cr to producer")
              cr
          } ~> processor.map { minutes =>
            log.info(s"[$graphName] Sending output to sink")
            minutes
          } ~> killSwitch ~> deskRecsSink
          ClosedShape
    }

    RunnableGraph
      .fromGraph(graph)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
  }
}
