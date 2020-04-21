package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import drt.shared.{PortStateDiff, SDateLike}

object PortStateTestActor {
  def apply(testProbe: TestProbe, now: () => SDateLike)(implicit system: ActorSystem): ActorRef = {
    val crunchStateMockActor: ActorRef = system.actorOf(Props(classOf[CrunchStateMockActor]), "crunch-state-mock")
    system.actorOf(Props(new PortStateTestActor(crunchStateMockActor, crunchStateMockActor, testProbe.ref, now, 100)), name = "port-state-actor")
  }
}

class PortStateTestActor(liveActor: ActorRef,
                         forecastActor: ActorRef,
                         probe: ActorRef,
                         now: () => SDateLike,
                         liveDaysAhead: Int)
  extends PortStateActor(liveActor, forecastActor, now, liveDaysAhead) {
  override def splitDiffAndSend(diff: PortStateDiff): Unit = {
    super.splitDiffAndSend(diff)
    probe ! state.immutable
  }
}
