package services.crunch.deskrecs

import actors.MinuteLookupsLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.RequestAndTerminateActor
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.routing.minutes.{QueueLoadsMinutesActor, QueueMinutesRouterActor, StaffMinutesRouterActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.CrunchMinute
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import test.TestActors.TestQueueLoadsMinutesActor
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.Map
import scala.concurrent.ExecutionContext


class MockPortStateActorForDeployments(probe: TestProbe, responseDelayMillis: Long = 0L) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.info(s"Completed")
      probe.ref ! StreamCompleted

    case StreamFailure(t) =>
      log.error(s"Failed", t)
      probe.ref ! StreamFailure

    case simMins: SimulationMinutes =>
      sender() ! Ack
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

case class TestMinuteLookups(queueProbe: ActorRef,
                             system: ActorSystem,
                             now: () => SDateLike,
                             expireAfterMillis: Int,
                             queuesByTerminal: Map[Terminal, Seq[Queue]],
                             deploymentsQueueSubscriber: ActorRef)
                            (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-minutes-lookup-kill-actor")

  override val queueLoadsMinutesActor: ActorRef = system.actorOf(Props(new QueueLoadsMinutesActor(queuesByTerminal.keys, queuesLoadsLookup, updatePassengerMinutes)))

  override val queueMinutesRouterActor: ActorRef = system.actorOf(Props(new TestQueueMinutesRouterActor(queueProbe, queuesByTerminal.keys, queuesLookup, updateCrunchMinutes, deploymentsQueueSubscriber)))

  override val staffMinutesRouterActor: ActorRef = system.actorOf(Props(new StaffMinutesRouterActor(queuesByTerminal.keys, staffLookup, updateStaffMinutes)))
}



