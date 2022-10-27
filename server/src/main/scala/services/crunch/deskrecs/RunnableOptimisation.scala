package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.GraphDSL.Implicits.port2flow
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink}
import akka.stream.{ClosedShape, KillSwitches, UniqueKillSwitch}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.europeLondonTimeZone
import services.{SDate, StreamSupervision, TimeLogger}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.NumericRange

object RunnableOptimisation {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val timeLogger: TimeLogger = TimeLogger("Optimisation", 1000, log)

  sealed trait ProcessingRequest extends Ordered[ProcessingRequest] {
    val localDate: LocalDate
    val offsetMinutes: Int
    val durationMinutes: Int
    lazy val start: SDateLike = SDate(localDate).addMinutes(offsetMinutes)
    lazy val end: SDateLike = start.addMinutes(durationMinutes)
    lazy val minutesInMillis: NumericRange[MillisSinceEpoch] = start.millisSinceEpoch until end.millisSinceEpoch by 60000

    override def compare(that: ProcessingRequest): Int =
      if (localDate < that.localDate) -1
      else if (localDate > that.localDate) 1
      else 0
  }

  case class CrunchRequest(localDate: LocalDate, offsetMinutes: Int, durationMinutes: Int) extends ProcessingRequest

  case class TerminalUpdateRequest(terminal: Terminal, localDate: LocalDate, offsetMinutes: Int, durationMinutes: Int) extends ProcessingRequest

  object CrunchRequest {
    def apply(millis: MillisSinceEpoch, offsetMinutes: Int, durationMinutes: Int): CrunchRequest = {
      val midnight = SDate(millis, europeLondonTimeZone)
        .addMinutes(-1 * offsetMinutes)
        .getLocalLastMidnight
      val localDate = midnight
        .toLocalDate

      CrunchRequest(localDate, offsetMinutes, durationMinutes)
    }
  }

  case class RemoveCrunchRequest(crunchRequest: ProcessingRequest)

  def createGraph[A](crunchRequestSource: SortedActorRefSource,
                     minutesSinkActor: ActorRef,
                     crunchRequestsToQueueMinutes: Flow[ProcessingRequest, A, NotUsed],
                     graphName: String,
                    ): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    val deskRecsSink = Sink.actorRefWithAck(minutesSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure)
    val ks = KillSwitches.single[A]

    val graph = GraphDSL.create(crunchRequestSource, ks)((_, _)) {
      implicit builder =>
        (crunchRequests, killSwitch) =>
          crunchRequests.out.map { cr =>
            log.info(s"[$graphName] Sending $cr to producer")
            cr
          } ~> crunchRequestsToQueueMinutes.map { minutes =>
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
