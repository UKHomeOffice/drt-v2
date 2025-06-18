package uk.gov.homeoffice.drt.service

import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.ports.PaxType
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaMachineReadable, VisaNational}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}

class EgateUptakeSimulationTest extends AnyWordSpec {
  "queueAllocationForEgateUptake" should {
    "update egate/desk pax based on given egate uptake, and leave non-egate pax alone" in {
      val allocation: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]] = Map(T1 -> Map(
        EeaMachineReadable -> Seq((EGate, 0.5), (EeaDesk, 0.5)),
        VisaNational -> Seq((NonEeaDesk, 1))),
      )
      val updatedUptake = 0.8
      val updated = EgateUptakeSimulation.queueAllocationForEgateUptake(allocation, updatedUptake)
      assert(updated == Map(T1 -> Map(
        EeaMachineReadable -> Seq((EGate, updatedUptake), (EeaDesk, 1 - updatedUptake)),
        VisaNational -> Seq((NonEeaDesk, 1))
      )))
    }
  }


}
