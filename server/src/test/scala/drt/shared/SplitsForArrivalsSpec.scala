package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff, SplitsForArrivals}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.SplitStyle.PaxNumbers
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits, TotalPaxSource, UniqueArrival}
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports._

import scala.collection.SortedSet

class SplitsForArrivalsSpec extends Specification {

  val now: MillisSinceEpoch = 10L
  val uniqueArrival: UniqueArrival = UniqueArrival(1, T1, 0L, PortCode("JFK"))

  "When I apply SplitsForArrivals to FlightsWithSplits" >> {
    "Given one new split and no arrivals" >> {
      "Then I should get an empty FlightsWithSplits" >> {
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(Splits(Set(), Historical, None, PaxNumbers))))
        val flights = FlightsWithSplits(Seq())

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff.empty
      }
    }

    "Given one new split and one matching arrival with no existing splits" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival with the new split" >> {
        val newSplits = Splits(Set(), Historical, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(newSplits)))
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"))
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set())))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival, Set(newSplits), Option(now))), Seq())
      }
    }

    "Given one new split and one matching arrival with an existing split with the same values as the new one" >> {
      "Then I should get an empty FlightsWithSplits" >> {
        val existingSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, None, None)), Historical, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(existingSplits)))
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"))
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(existingSplits))))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff.empty
      }
    }

    "Given one new split and one matching arrival with an existing split of the same source" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival updated with the new split" >> {
        val existingSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, None, None)), Historical, None, PaxNumbers)
        val newSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(newSplits)))
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"))
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(existingSplits))))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival, Set(newSplits), Option(now))), Seq())
      }
    }

    "Given one new split and one matching arrival with an existing split from a different source" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival with both sets of splits" >> {
        val existingSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, None, None)), Historical, None, PaxNumbers)
        val newSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(newSplits)))
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"))
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(existingSplits))))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival.copy(FeedSources = Set(ApiFeedSource), ApiPax = Option(1) ,
          TotalPax = SortedSet(TotalPaxSource(1,ApiFeedSource,Some(ApiSplitsWithHistoricalEGateAndFTPercentages)))
        ), Set(newSplits, existingSplits), Option(now))), Seq())
      }
    }

    "Given one new split and one matching arrival with 2 existing splits, one from the same source" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival with the newly updated split along with the other pre-existing split" >> {
        val existingSplits1 = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, None, None)), Historical, None, PaxNumbers)
        val existingSplits2 = Splits(Set(ApiPaxTypeAndQueueCount(VisaNational, EGate, 1, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)
        val newSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(newSplits)))
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"))
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(existingSplits1, existingSplits2))))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival.copy(FeedSources = Set(ApiFeedSource), ApiPax = Option(1) ,
          TotalPax = SortedSet(TotalPaxSource(1,ApiFeedSource,Some(ApiSplitsWithHistoricalEGateAndFTPercentages)))), Set(newSplits, existingSplits1), Option(now))), Seq())
      }
    }

    "Given 2 splits and two arrivals, with only one matching and with an existing split" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival updated with the correct new split" >> {
        val uniqueArrival2 = UniqueArrival(200, T1, 0L, PortCode("JFK"))
        val existingSplits1 = Splits(Set(ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val existingSplits2 = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, None, None)), Historical, None, PaxNumbers)
        val newSplits1 = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val newSplits2 = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(newSplits1), uniqueArrival2 -> Set(newSplits2)))
        val arrival1 = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"))
        val arrival2 = ArrivalGenerator.arrival(iata = "FR1234", terminal = T1, origin = PortCode("JFK"))
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival1, Set(existingSplits1)), ApiFlightWithSplits(arrival2, Set(existingSplits2))))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival1, Set(newSplits1), Option(now))), Seq())
      }
    }
  }
}
