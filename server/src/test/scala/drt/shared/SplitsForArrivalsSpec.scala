package drt.shared

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff, SplitsForArrivals}
import drt.shared.PaxTypes.{EeaMachineReadable, EeaNonMachineReadable, VisaNational}
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.SplitSources.Historical
import drt.shared.Terminals.T1
import org.specs2.mutable.Specification

class SplitsForArrivalsSpec extends Specification {

  val now: MillisSinceEpoch = 10L

  "When I apply SplitsForArrivals to FlightsWithSplits" >> {
    "Given one split and no arrivals" >> {
      "Then I should get an empty FlightsWithSplits" >> {
        val uniqueArrival = UniqueArrival(1, T1, 0L)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(Splits(Set(), Historical, None, PaxNumbers))))
        val flights = FlightsWithSplits(Seq())

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff.empty
      }
    }

    "Given one split and one matching arrival with no existing splits" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival with the new split" >> {
        val uniqueArrival = UniqueArrival(1, T1, 0L)
        val newSplits = Splits(Set(), Historical, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(newSplits)))
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1)
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set())))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival, Set(newSplits), Option(now))), Seq())
      }
    }

    "Given one split and one matching arrival with an existing split" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival updated with the new split" >> {
        val uniqueArrival = UniqueArrival(1, T1, 0L)
        val existingSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, None, None)), Historical, None, PaxNumbers)
        val newSplits = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival -> Set(newSplits)))
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1)
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(existingSplits))))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival, Set(newSplits), Option(now))), Seq())
      }
    }

    "Given 2 splits and two arrivals, with only one matching and with an existing split" >> {
      "Then I should get a FlightsWithSplits containing the matching arrival updated with the correct new split" >> {
        val uniqueArrival1 = UniqueArrival(1, T1, 0L)
        val uniqueArrival2 = UniqueArrival(200, T1, 0L)
        val existingSplits1 = Splits(Set(ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val existingSplits2 = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, None, None)), Historical, None, PaxNumbers)
        val newSplits1 = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val newSplits2 = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val splitsForArrivals = SplitsForArrivals(Map(uniqueArrival1 -> Set(newSplits1), uniqueArrival2 -> Set(newSplits2)))
        val arrival1 = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1)
        val arrival2 = ArrivalGenerator.arrival(iata = "FR1234", terminal = T1)
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival1, Set(existingSplits1)), ApiFlightWithSplits(arrival2, Set(existingSplits2))))

        val updated = splitsForArrivals.diff(flights, now)

        updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival1, Set(newSplits1), Option(now))), Seq())
      }
    }
  }
}
