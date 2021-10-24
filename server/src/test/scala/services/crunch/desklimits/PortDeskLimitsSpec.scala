package services.crunch.desklimits

import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.T1

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class PortDeskLimitsSpec extends CrunchTestLike {
  "Given a PortDeskLimits with an egates provider giving 3 banks followed by 1 bank from 10 milliseconds" >> {
    "When I ask for the max desks for milliseconds 9 & 10" >> {
      "I should see 3 max followed by 1 max" >> {
        val threeBanks = IndexedSeq(EgateBank(IndexedSeq(true)), EgateBank(IndexedSeq(true)), EgateBank(IndexedSeq(true)))
        val oneBankUpdate = IndexedSeq(EgateBank(IndexedSeq(true, true)))
        val eventualUpdates = Future.successful(EgateBanksUpdates(List(EgateBanksUpdate(0L, threeBanks), EgateBanksUpdate(10L, oneBankUpdate))))
        val portDeskLimits = PortDeskLimits.flexed(defaultAirportConfig, Terminal => eventualUpdates)

        val maxDesks = Await.result(portDeskLimits(T1).maxDesks(9L to 10L, Queues.EGate, Map()), 1.second)
        maxDesks === List(3, 1)
      }
    }
  }
}
