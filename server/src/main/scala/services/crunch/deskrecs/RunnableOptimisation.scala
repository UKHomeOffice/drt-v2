package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.GraphDSL.Implicits.SourceShapeArrow
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink}
import akka.stream.{Attributes, ClosedShape, KillSwitches, UniqueKillSwitch}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.PortStateMinutes
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.europeLondonTimeZone
import services.{SDate, StreamSupervision, TimeLogger}
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
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

  def createGraph[A, B <: WithTimeAccessor](crunchRequestSource: SortedActorRefSource,
                                            deskRecsSinkActor: ActorRef,
                                            crunchRequestsToQueueMinutes: Flow[ProcessingRequest, PortStateMinutes[A, B], NotUsed])
                                           (implicit system: ActorSystem): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    val deskRecsSink = Sink.actorRefWithAck(deskRecsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure)
    val ks = KillSwitches.single[PortStateMinutes[A, B]]

    val graph = GraphDSL.create(crunchRequestSource, ks)((_, _)) {
      implicit builder =>
        (crunchRequests, killSwitch) =>
          crunchRequests ~> crunchRequestsToQueueMinutes.withAttributes(Attributes.inputBuffer(initial = 1, max = 1)) ~> killSwitch ~> deskRecsSink
          ClosedShape
    }

    RunnableGraph
      .fromGraph(graph)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .withAttributes(Attributes.inputBuffer(initial = 1, max = 1))
  }
}
