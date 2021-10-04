package services

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.ports.config.{Bhx, Ema, Lhr}
import uk.gov.homeoffice.drt.ports.{AirportConfigLike, Queues}

class AirportConfigSpec extends Specification {
  "Airport Config" >> {
    "LHR Airport Config" should {
      val splitTotal = Lhr.config.terminalPaxSplits(T2).splits.map(_.ratio).sum

      splitTotal must beCloseTo(1, delta = 0.000001)
    }

    splitOrder(Lhr, T2, List(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk, Queues.FastTrack))
    splitOrder(Ema, T1, List(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk))
    splitOrder(Bhx, T1, List(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk))
    splitOrder(Bhx, T2, List(Queues.EeaDesk, Queues.NonEeaDesk))
  }

  private def splitOrder(port: AirportConfigLike, terminalName: Terminal, expectedSplitOrder: List[Queue]): Fragment =
    s"${port.config.portCode} $terminalName split order should be $expectedSplitOrder" >> {
      port.config.queueTypeSplitOrder(terminalName) === expectedSplitOrder
    }
}
