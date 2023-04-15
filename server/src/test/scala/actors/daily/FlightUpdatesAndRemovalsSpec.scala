package actors.daily

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplitsDiff}

class FlightUpdatesAndRemovalsSpec extends Specification {
  private val update1000 = 1000L
  private val update1500 = 1500L
  private val update2000 = 2000L
  private val arrival1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2022-05-01T00:25")
  private val fws1 = ApiFlightWithSplits(arrival1, Set(), lastUpdated = Option(update1000))
  private val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2022-05-01T12:40")
  private val fws2 = ApiFlightWithSplits(arrival2, Set(), lastUpdated = Option(update1500))
  private val arrival3 = ArrivalGenerator.arrival(iata = "BA0003", schDt = "2022-05-01T20:15")
  private val fws3 = ApiFlightWithSplits(arrival3, Set(), lastUpdated = Option(update2000))
  "Given an empty FlightUpdatesAndRemovals" >> {
    "When I add an update it should retain it in its updates Map" >> {
      val updatesAndRemovals = FlightUpdatesAndRemovals.empty ++ Seq(fws1)
      updatesAndRemovals === FlightUpdatesAndRemovals(Seq(fws1), Seq())
    }
  }

  "Given a FlightUpdatesAndRemovals containing an arrival and a removal" >> {
    "When I apply a FlightsWithSplitsDiff it should contain the updates and remove and record any removals" >> {
      val removals = Seq((update1500, fws3.unique))
      val updatesAndRemovals = FlightUpdatesAndRemovals(Seq(fws1, fws2), removals)
      val fws1v2 = fws1.copy(arrival1.copy(ActPax = Option(100)))
      val diff = FlightsWithSplitsDiff(Seq(fws1v2), Seq(fws2.unique))

      val expected = FlightUpdatesAndRemovals(Seq(fws1v2), removals ++ Seq((update2000, fws2.unique)))

      updatesAndRemovals.apply(diff, update2000) === expected
    }
  }

  "Given a FlightUpdatesAndRemovals containing arrivals and removals" >> {
    "When I ask for updates since 1500 it should return flights and removals with update times later than 1500" >> {
      val updatesAndRemovals = FlightUpdatesAndRemovals(Seq(fws1, fws2, fws3), Seq((update1500, fws1.unique), (update2000, fws2.unique)))
      updatesAndRemovals.updatesSince(update1500) === FlightsWithSplitsDiff(Seq(fws3), Set(fws2.unique))
    }
    "When I purge items older than 1500 it should return flights and removals with update times earlier than 1500" >> {
      val updatesAndRemovals = FlightUpdatesAndRemovals(
        Seq(fws1, fws2, fws3),
        Seq((update1000, fws1.unique), (update1500, fws2.unique), (update2000, fws3.unique)))
      val expected = FlightUpdatesAndRemovals(
        Seq(fws2, fws3),
        Seq((update1500, fws2.unique), (update2000, fws3.unique)))

      updatesAndRemovals.purgeOldUpdates(update1500) === expected
    }
  }
}
