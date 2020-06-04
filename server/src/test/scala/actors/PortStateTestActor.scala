package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{PortStateDiff, SDateLike}
import services.graphstages.Crunch

object PortStateTestActor {
  def apply(testProbe: TestProbe, now: () => SDateLike, queues: Map[Terminal, Seq[Queue]])(implicit system: ActorSystem): ActorRef = {
    val crunchStateMockActor: ActorRef = system.actorOf(Props(classOf[CrunchStateMockActor]), "crunch-state-mock")
    system.actorOf(Props(new PortStateTestActor(crunchStateMockActor, crunchStateMockActor, testProbe.ref, now, 100, queues)), name = "port-state-actor")
  }
}

class PortStateTestActor(liveActor: ActorRef,
                         forecastActor: ActorRef,
                         probe: ActorRef,
                         now: () => SDateLike,
                         liveDaysAhead: Int,
                         queues: Map[Terminal, Seq[Queue]])
  extends PortStateActor(liveActor, forecastActor, now, liveDaysAhead, queues) {
  override def splitDiffAndSend(diff: PortStateDiff): Unit = {
    super.splitDiffAndSend(diff)
    probe ! state.immutable
  }
}
