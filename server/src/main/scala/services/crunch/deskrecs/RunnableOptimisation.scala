package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.GraphDSL.Implicits.port2flow
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, KillSwitches, OverflowStrategy, UniqueKillSwitch}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.dates.LocalDate
import drt.shared.{PortStateQueueMinutes, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import services.{SDate, TimeLogger}
import services.graphstages.Crunch.europeLondonTimeZone

import scala.collection.immutable.NumericRange
import scala.concurrent.ExecutionContext

object RunnableOptimisation {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val timeLogger: TimeLogger = TimeLogger("Optimisation", 1000, log)

  case class CrunchRequest(localDate: LocalDate, offsetMinutes: Int, durationMinutes: Int) extends Ordered[CrunchRequest] {
    lazy val start: SDateLike = SDate(localDate).addMinutes(offsetMinutes)
    lazy val end: SDateLike = start.addMinutes(durationMinutes)
    lazy val minutesInMillis: NumericRange[MillisSinceEpoch] = start.millisSinceEpoch until end.millisSinceEpoch by 60000

    override def compare(that: CrunchRequest): Int =
      if (localDate < that.localDate) -1
      else if (localDate > that.localDate) 1
      else 0
  }

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

  def createGraph(deskRecsSinkActor: ActorRef, crunchRequestsToQueueMinutes: Flow[CrunchRequest, PortStateQueueMinutes, NotUsed])
                 (implicit ec: ExecutionContext): RunnableGraph[(SourceQueueWithComplete[CrunchRequest], UniqueKillSwitch)] = {

    val crunchRequestSource = Source.queue[CrunchRequest](1, OverflowStrategy.backpressure)
    val deskRecsSink = Sink.actorRefWithAck(deskRecsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure)
    val ks = KillSwitches.single[PortStateQueueMinutes]

    val graph = GraphDSL.create(crunchRequestSource, ks)((_, _)) {
      implicit builder =>
        (crunchRequests, killSwitch) =>
          crunchRequests.out ~> crunchRequestsToQueueMinutes ~> killSwitch ~> deskRecsSink
          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
