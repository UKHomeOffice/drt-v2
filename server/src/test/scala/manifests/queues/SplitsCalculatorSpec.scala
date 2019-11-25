package manifests.queues

import drt.shared.PaxTypes._
import drt.shared.Terminals.T2
import drt.shared.airportconfig.Bhx
import drt.shared.{AirportConfig, ApiPaxTypeAndQueueCount, Percentage, Queues, Splits}
import queueus.{B5JPlusWithTransitTypeAllocator, PaxTypeQueueAllocation, TerminalQueueAllocatorWithFastTrack}
import services.SDate
import services.crunch.CrunchTestLike

class SplitsCalculatorSpec extends CrunchTestLike {
  private val config: AirportConfig = Bhx.config
  val paxTypeQueueAllocation = PaxTypeQueueAllocation(
    B5JPlusWithTransitTypeAllocator(SDate("2019-05-01T00:00:00")),
    TerminalQueueAllocatorWithFastTrack(config.terminalPaxTypeQueueAllocation))

  val splitsCalculator = SplitsCalculator(config.portCode, paxTypeQueueAllocation, config.terminalPaxSplits)

  "Given a splits calculator with BHX's terminal pax splits " +
    "When I ask for the default splits for T2 " +
    "I should see no EGate split" >> {
    val result = splitsCalculator.terminalDefaultSplits(T2)

    val expected = Set(Splits(Set(
      ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 4.0, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 0.0, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 92.0, None),
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 4.0, None)),
      "TerminalAverage", None, Percentage))

    result === expected
  }
}
