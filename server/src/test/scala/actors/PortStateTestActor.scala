package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{PortState, PortStateDiff, SDateLike}

object PortStateTestActor {
  def apply(testProbe: TestProbe, now: () => SDateLike, queues: Map[Terminal, Seq[Queue]], initialPortState: Option[PortState])(implicit system: ActorSystem): ActorRef = {
    val crunchStateMockLiveProps: Props = Props(new CrunchStateMockActor(initialPortState))
    val crunchStateMockFcstProps: Props = Props(new CrunchStateMockActor(None))
    system.actorOf(Props(new PortStateTestActor(crunchStateMockLiveProps, crunchStateMockFcstProps, testProbe.ref, now, 100, queues)), name = "port-state-actor")
  }
}

class PortStateTestActor(liveProps: Props,
                         forecastProps: Props,
                         probe: ActorRef,
                         now: () => SDateLike,
                         liveDaysAhead: Int,
                         queues: Map[Terminal, Seq[Queue]])
  extends PortStateActor(liveProps, forecastProps, now, liveDaysAhead, queues, replayMaxCrunchStateMessages = 1000) {
  override def splitDiffAndSend(diff: PortStateDiff): Unit = {
    super.splitDiffAndSend(diff)
    probe ! state.immutable
  }
}
