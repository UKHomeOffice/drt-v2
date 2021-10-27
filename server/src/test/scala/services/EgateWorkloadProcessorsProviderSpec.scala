package services

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates}

class EgateWorkloadProcessorsProviderSpec extends Specification {
  "A processor provider" should {
    "Give the expected number of processors given an airportConfig" in {
      val banks = EgateBank.fromAirportConfig(Iterable(10, 5))
      val updates = EgateBanksUpdates(List(EgateBanksUpdate(0L, banks)))
      val processor = WorkloadProcessorsProvider(updates.forPeriod(0L to 1L))

      processor.forMinute(0).capacityForServers(2) === 15
    }
  }
}
