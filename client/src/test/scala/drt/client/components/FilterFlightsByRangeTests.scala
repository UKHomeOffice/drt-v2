package drt.client.components

import drt.client.components.TerminalContentComponent.filterFlightsByRange
import drt.client.services.JSDateConversions.SDate
import drt.client.services.TimeRangeHours
import drt.shared.ApiFlightWithSplits
import japgolly.scalajs.react.test
import utest.{TestSuite, _}


object FilterFlightsByRangeTests extends TestSuite {

  import ApiFlightGenerator._

  test.WebpackRequire.ReactTestUtils

  def tests = TestSuite {
    "Given an hour range of 10 to 14" - {
      val range = TimeRangeHours(10, 14)
      val displayDate = SDate.parse("2017-01-01T11:00:00Z")

      "When a flight has a scheduled date within that range then it should appear within the filtered range" - {
        val withinRangeFlight = ApiFlightWithSplits(apiFlight("2017-01-01T11:00:00Z"), Set())

        val result = filterFlightsByRange(displayDate, range, List(withinRangeFlight))
        val expected = List(withinRangeFlight)

        assert(result == expected)

      }

      "When a flight has has no times within the range, then it should not appear in the filtered range" - {
        val notWithinRangeFlight = ApiFlightWithSplits(apiFlight("2017-01-01T09:00:00Z"), Set())

        val result = filterFlightsByRange(displayDate, range, List(notWithinRangeFlight))
        val expected = List()

        assert(result == expected)
      }

      "When a flight has an PCP Date within that range then it should appear within the filtered range" - {
        val withinRangeFlight = ApiFlightWithSplits(apiFlight(SchDT = "2017-01-01T09:00:00Z", PcpTime = SDate.parse("2017-01-01T11:00:00Z").millisSinceEpoch), Set())

        val result = filterFlightsByRange(displayDate, range, List(withinRangeFlight))
        val expected = List(withinRangeFlight)

        assert(result == expected)
      }

      "When a flight has has a time within the range but is for the previous day, it should not show up in the filtered range" - {
        val range = TimeRangeHours(10, 24)
        val displayDate = SDate.parse("2017-01-02T11:00:00Z")
        val notWithinRangeFlight = ApiFlightWithSplits(apiFlight(SchDT = "2017-01-01T23:00:00Z", ActChoxDT = "2017-01-01T23:00:00Z"), Set())

        val result = filterFlightsByRange(displayDate, range, List(notWithinRangeFlight))
        val expected = List()

        assert(result == expected)
      }
    }
  }

  def mkMillis(t: String): Long = SDate.parse(t).millisSinceEpoch
}
