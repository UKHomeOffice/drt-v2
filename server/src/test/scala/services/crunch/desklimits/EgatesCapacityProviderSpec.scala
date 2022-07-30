package services.crunch.desklimits

import services.{WorkloadProcessors, WorkloadProcessorsProvider}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class EgatesCapacityProviderSpec extends CrunchTestLike {
  "Given a capacity provider" >> {
    val threeBanks = IndexedSeq(EgateBank(IndexedSeq(true, true)), EgateBank(IndexedSeq(true, true)), EgateBank(IndexedSeq(true)))
    val oneBank = IndexedSeq(EgateBank(IndexedSeq(true, true)))
    val eventualUpdates = Future.successful(EgateBanksUpdates(List(EgateBanksUpdate(0L, threeBanks), EgateBanksUpdate(10L, oneBank))))
    val provider = EgatesCapacityProvider(() => eventualUpdates)
    val capacities: WorkloadProcessorsProvider = Await.result(provider.capacityForPeriod(9L to 10L), 1.second)

    capacities === WorkloadProcessorsProvider(IndexedSeq(WorkloadProcessors(threeBanks), WorkloadProcessors(oneBank)))
  }
}
