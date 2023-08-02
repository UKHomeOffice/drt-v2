package drt.shared

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, FlightsWithSplitsDiff, Splits}
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxAge}
import uk.gov.homeoffice.drt.ports.PaxTypes.{NonVisaNational, VisaNational}
import uk.gov.homeoffice.drt.ports.Queues.NonEeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages

class ArrivalsDiffTest extends Specification {
  "Given an existing flight with splits and an arrival diff with the same arrival" >> {
    "The diff should not contain any updates" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", sch = 1000L)
      val splits = Set(
        Splits(Set(ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 10d, Option(Map(Nationality("GBR") -> 10)), Option(Map(PaxAge(35) -> 10)))), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC)),
        Splits(Set(ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 2d, Option(Map(Nationality("GBR") -> 2)), Option(Map(PaxAge(31) -> 2)))), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC)),
      )
      val existingFlight = ApiFlightWithSplits(arrival, splits, None)
      val incomingArrival = arrival
      ArrivalsDiff(Seq(incomingArrival), Seq()).diffWith(FlightsWithSplits(Seq(existingFlight)), 10L) === FlightsWithSplitsDiff(Seq(), Seq())
    }
  }
}
