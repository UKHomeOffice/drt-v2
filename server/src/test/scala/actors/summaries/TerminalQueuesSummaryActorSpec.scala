package actors.summaries

import akka.actor.{PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.SDateLike
import drt.shared.Terminals.{T1, Terminal}
import services.SDate
import services.crunch.CrunchTestLike


class TestTerminalQueuesSummaryActor(probe: TestProbe,
                                     year: Int,
                                     month: Int,
                                     day: Int,
                                     terminal: Terminal,
                                     now: () => SDateLike) extends TerminalQueuesSummaryActor(year, month, day, terminal, now) {
  override def preStart(): Unit = {
    probe.ref ! persistenceId
    super.preStart()
    self ! PoisonPill
  }
}

class TerminalQueuesSummaryActorSpec extends CrunchTestLike with ImplicitSender {
  val probe: TestProbe = TestProbe("summaries")

  "Given a BST date of 2020-06-25" >> {
    "When I ask for a TerminalQueuesSummaryActor" >> {
      "I should find the persistenceId is 'terminal-queues-summary-t1-2020-06-25'" >> {
        system.actorOf(Props(new TestTerminalQueuesSummaryActor(probe, 2020, 6, 25, T1, () => SDate("2020-06-01"))))
        probe.expectMsg("terminal-queues-summary-t1-2020-06-25")
        success
      }
    }
  }
}
