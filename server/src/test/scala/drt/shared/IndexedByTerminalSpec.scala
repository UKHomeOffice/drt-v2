package drt.shared

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{PortStateMutable, PortStateUpdates}
import org.specs2.mutable.SpecificationLike

class IndexedByTerminalSpec extends SpecificationLike {
  "Given an empty PortStateMutable " >> {
    "When I ask for updates since a time " +
      "I should get a None" >> {
      val ps = new PortStateMutable

      val result = ps.updates(0L, Long.MinValue, Long.MaxValue)

      result === None
    }

    "When I add an arrival and ask for updates since a point in time earlier than the arrival's updated time " +
      "I should get a the arrival in the diff" >> {
      val ps = new PortStateMutable

      val arrival = ApiFlightWithSplits(ArrivalGenerator.arrival(), Set(), lastUpdated = Option(1L))
      ps.flights +++= Seq(arrival)

      val result = ps.updates(0L, Long.MinValue, Long.MaxValue)

      val expected = Option(PortStateUpdates(1L, Set(arrival), Set(), Set()))

      result === expected
    }

    "When I add an arrival and ask for updates since a point in time later than the arrival's updated time " +
      "I should get a None" >> {
      val ps = new PortStateMutable

      val arrival = ApiFlightWithSplits(ArrivalGenerator.arrival(), Set(), lastUpdated = Option(1L))
      ps.flights +++= Seq(arrival)

      val result = ps.updates(10L, Long.MinValue, Long.MaxValue)

      val expected = None

      result === expected
    }

    "When I add an arrival, and purge updates up to a point after the arrival's updated time, and ask for updates since a point in time earlier than the arrival's updated time " +
      "I should get the arrival" >> {
      val ps = new PortStateMutable

      val arrivalUpdateTime = 1L
      val purgeEarlierThanTime = 5L
      val updatesSinceTime = 0L

      val arrival = ApiFlightWithSplits(ArrivalGenerator.arrival(), Set(), lastUpdated = Option(arrivalUpdateTime))
      ps.flights +++= Seq(arrival)

      ps.purgeRecentUpdates(purgeEarlierThanTime)

      val result = ps.updates(updatesSinceTime, Long.MinValue, Long.MaxValue)

      val expected = Option(PortStateUpdates(1L, Set(arrival), Set(), Set()))

      result === expected
    }

    "When I add 2 arrivals, and purge updates up to a point after one of the arrival's updated time, and ask for updates since a point in time earlier than the remaining arrival's updated time " +
      "I should get the arrival" >> {
      val ps = new PortStateMutable

      val arrival1UpdateTime = 1L
      val arrival2UpdateTime = 10L
      val purgeEarlierThanTime = 5L
      val updatesSinceTime = 0L

      val arrival1 = ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA001", origin = "JFK", schDt = "2019-01-01T00:00"), Set(), lastUpdated = Option(arrival1UpdateTime))
      val arrival2 = ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA001", origin = "JNB", schDt = "2019-01-01T05:00"), Set(), lastUpdated = Option(arrival2UpdateTime))
      ps.flights +++= Seq(arrival1, arrival2)

      ps.purgeRecentUpdates(purgeEarlierThanTime)

      val result = ps.updates(updatesSinceTime, Long.MinValue, Long.MaxValue)

      val expected = Option(PortStateUpdates(10L, Set(arrival1, arrival2), Set(), Set()))

      result === expected
    }
  }
}
