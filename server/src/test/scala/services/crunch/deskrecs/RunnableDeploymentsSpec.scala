package services.crunch.deskrecs

import actors.MinuteLookupsLike
import actors.daily.RequestAndTerminateActor
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.routing.minutes.{QueueLoadsMinutesActor, QueueMinutesRouterActor, StaffMinutesRouterActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.StatusReply
import akka.testkit.TestProbe
import drt.shared.CrunchApi.CrunchMinute
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext


class MockPortStateActorForDeployments(probe: TestProbe, responseDelayMillis: Long = 0L) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case StreamInitialized =>
      sender() ! StatusReply.Ack

    case StreamCompleted =>
      log.info(s"Completed")
      probe.ref ! StreamCompleted

    case StreamFailure(t) =>
      log.error(s"Failed", t)
      probe.ref ! StreamFailure

    case simMins: SimulationMinutes =>
      sender() ! StatusReply.Ack
      probe.ref ! simMins
  }
}

class TestQueueMinutesRouterActor(probe: ActorRef,
                                  terminals: Iterable[Terminal],
                                  lookup: MinutesLookup[CrunchMinute, TQM],
                                  updateMinutes: MinutesUpdate[CrunchMinute, TQM],
                                  updatesSubscriber: ActorRef) extends QueueMinutesRouterActor(terminals, lookup, updateMinutes) {

  override def receive: Receive = testReceives

  def testReceives: Receive = {
    case msg =>
      probe ! msg
      super.receive(msg)
  }
}




