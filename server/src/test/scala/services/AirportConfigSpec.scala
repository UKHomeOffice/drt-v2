package services

import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaNonMachineReadableToDesk}
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared.airportconfig.{Bhx, Ema, Lhr}
import drt.shared.{AirportConfigLike, AirportConfigs, PortCode, Queues}
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

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

  "LHR processing times should reflect both the standard times and the additional plf times" >> {
    val config = AirportConfigs.confByPort(PortCode("LHR"))
    val eeaMrDesk = config.terminalProcessingTimes(T2)(eeaMachineReadableToDesk)
    val expected = (25d + 43d) / 60

    eeaMrDesk === expected
  }
}
