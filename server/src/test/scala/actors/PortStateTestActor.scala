package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import drt.shared.{PortStateDiff, SDateLike}

object PortStateTestActor {
  def apply(testProbe: TestProbe, now: () => SDateLike)(implicit system: ActorSystem): ActorRef = {
    val crunchStateMockActor: Props = Props(classOf[CrunchStateMockActor])
    system.actorOf(Props(new PortStateTestActor(crunchStateMockActor, crunchStateMockActor, testProbe.ref, now, 100)), name = "port-state-actor")
  }
}

class PortStateTestActor(liveProps: Props,
                         forecastProps: Props,
                         probe: ActorRef,
                         now: () => SDateLike,
                         liveDaysAhead: Int)
  extends PortStateActor(liveProps, forecastProps, now, liveDaysAhead, exitOnQueueException = false) {
  override def splitDiffAndSend(diff: PortStateDiff): Unit = {
    super.splitDiffAndSend(diff)
    probe ! state.immutable
  }
}
