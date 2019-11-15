package services

import drt.shared.FlightsApi.TerminalName
import drt.shared.airportconfig.{Bhx, Ema, Lhr}
import drt.shared.{AirportConfigLike, Queues}
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

class AirportConfigSpec extends Specification {
  "Airport Config" >> {
    "LHR Airport Config" should {
      val splitTotal = Lhr.config.terminalPaxSplits("T2").splits.map(_.ratio).sum

      splitTotal must beCloseTo(1, delta = 0.000001)
    }

    splitOrder(Lhr, "T2", List(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk, Queues.FastTrack))
    splitOrder(Ema, "T1", List(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk))
    splitOrder(Bhx, "T1", List(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk))
    splitOrder(Bhx, "T2", List(Queues.EeaDesk, Queues.NonEeaDesk))
  }

  private def splitOrder(port: AirportConfigLike, terminalName: TerminalName, expectedSplitOrder: List[String]): Fragment =
    s"${port.config.portCode} $terminalName split order should be $expectedSplitOrder" >> {
      port.config.splitOrder(terminalName) === expectedSplitOrder
    }
}
