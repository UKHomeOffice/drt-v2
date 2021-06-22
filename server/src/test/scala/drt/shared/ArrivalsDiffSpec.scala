package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.SplitRatiosNs.SplitSources.Historical
import org.specs2.mutable.Specification

class ArrivalsDiffSpec extends Specification {
  val now: MillisSinceEpoch = 10L

  "When I apply ArrivalsDiff to FlightsWithSplits" >> {
    "Given no new arrivals and" >> {
      val arrivalsDiff = ArrivalsDiff(Seq(), Seq())

      "No existing flights" >> {
        val flights = FlightsWithSplits(Seq())

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = arrivalsDiff.diffWith(flights, now)
          updated === FlightsWithSplitsDiff.empty
        }
      }

      "One existing flight" >> {
        val arrival = ArrivalGenerator.arrival(iata = "BA0001")
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set())))

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = arrivalsDiff.diffWith(flights, now)
          updated === FlightsWithSplitsDiff.empty
        }
      }
    }

    "Given one new arrival and" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", status = ArrivalStatus("new status"))
      val arrivalsDiff = ArrivalsDiff(Seq(arrival), Seq())

      "No existing flights" >> {
        val flights = FlightsWithSplits(Seq())

        "Then I should get a FlightsWithSplits containing the new arrival" >> {
          val updated = arrivalsDiff.diffWith(flights, now)
          updated === FlightsWithSplitsDiff(Seq(ApiFlightWithSplits(arrival, Set(), Option(now))), Seq())
        }
      }

      "One existing flight with splits that matches and has the same arrival" >> {
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set())))

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = arrivalsDiff.diffWith(flights, now)
          updated === FlightsWithSplitsDiff.empty
        }
      }

      "One existing flight with splits that matches and has an older arrival" >> {
        val olderArrival = arrival.copy(Status = ArrivalStatus("old status"))
        val splits = Splits(Set(), Historical, None, PaxNumbers)
        val fws = ApiFlightWithSplits(olderArrival, Set(splits))
        val flights = FlightsWithSplits(Seq(fws))

        "Then I should get a FlightsWithSplits with the updated arrival and existing splits" >> {
          val updated = arrivalsDiff.diffWith(flights, now)
          updated === FlightsWithSplitsDiff(Seq(fws.copy(apiFlight = arrival, lastUpdated = Option(now))), Seq())
        }
      }
    }
  }
}
