package services.crunch.desklimits

import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class EgatesCapacityProviderSpec extends CrunchTestLike {
  "Given a capacity provider" >> {
    val threeBanks = IndexedSeq(EgateBank(IndexedSeq(true, true)), EgateBank(IndexedSeq(true, true)), EgateBank(IndexedSeq(true)))
    val oneBank = IndexedSeq(EgateBank(IndexedSeq(true, true)))
    val eventualUpdates = Future.successful(EgateBanksUpdates(List(EgateBanksUpdate(0L, threeBanks), EgateBanksUpdate(10L, oneBank))))
    val provider = EgatesCapacityProvider(() => eventualUpdates, IndexedSeq(EgateBank(IndexedSeq(true))))
    val capacities = Await.result(provider.capacityForPeriod(9L to 10L), 1.second)

    capacities === List(3, 1)
  }
}
