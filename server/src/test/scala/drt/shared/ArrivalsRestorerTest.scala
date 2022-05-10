package drt.shared

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.T1

class ArrivalsRestorerTest extends Specification {
  "Given two consecutive updates to an arrival where the first contains the baggage id, and the second doesn't" >> {
    "The arrival should retain the baggage id" >> {
      val restorer = new ArrivalsRestorer[Arrival]
      val update1 = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, sch = 0L, est = 0, baggageReclaimId = Option("1"))
      val update2 = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, sch = 0L, est = 60000)
      restorer.applyUpdates(Seq(update1))
      restorer.applyUpdates(Seq(update2))

      restorer.arrivals.values.head === update1.copy(Estimated = Option(60000))
    }
  }
}
