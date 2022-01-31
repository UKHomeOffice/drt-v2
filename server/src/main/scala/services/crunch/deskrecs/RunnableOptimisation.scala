package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.GraphDSL.Implicits.SourceShapeArrow
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink}
import akka.stream.{ClosedShape, KillSwitches, UniqueKillSwitch}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.PortStateQueueMinutes
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.europeLondonTimeZone
import services.{SDate, StreamSupervision, TimeLogger}
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.NumericRange

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

  case class RemoveCrunchRequest(crunchRequest: CrunchRequest)

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

  def createGraph(crunchRequestSource: SortedActorRefSource,
                  deskRecsSinkActor: ActorRef,
                  crunchRequestsToQueueMinutes: Flow[CrunchRequest, PortStateQueueMinutes, NotUsed])
                 (implicit system: ActorSystem): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    val deskRecsSink = Sink.actorRefWithAck(deskRecsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure)
    val ks = KillSwitches.single[PortStateQueueMinutes]

    val graph = GraphDSL.create(crunchRequestSource, ks)((_, _)) {
      implicit builder =>
        (crunchRequests, killSwitch) =>
          crunchRequests ~> crunchRequestsToQueueMinutes ~> killSwitch ~> deskRecsSink
          ClosedShape
    }

    RunnableGraph
      .fromGraph(graph)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
  }
}
