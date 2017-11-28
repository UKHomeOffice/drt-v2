package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.client.services.{CustomWindow, TimeRangeHours}
import drt.shared.CrunchApi.{CrunchMinute, CrunchState, StaffMinute}
import drt.shared.{ApiFlightWithSplits, Queues}
import utest.{TestSuite, _}

object FilterCrunchByRangeTests extends TestSuite {

  import ApiFlightGenerator._
  import TerminalContentComponent._

  def tests = TestSuite {
    "Given an hour range of 10 to 14" - {
      val range = CustomWindow(10, 14)
      val dateWithinRange = SDate.parse("2017-01-01T11:00:00Z")
      val dateOutsideRange = SDate.parse("2017-01-01T09:00:00Z")

      "When a CrunchState contains minutes within the range, then they should remain after the filter" - {
        val crunchMinuteWithinRange = CrunchMinute("T1", Queues.EeaDesk, dateWithinRange.millisSinceEpoch, 0, 0, 0, 0)
        val staffMinuteWithinRange = StaffMinute("T1", dateWithinRange.millisSinceEpoch, 0, 0, 0)
        val flightWithinRange = ApiFlightWithSplits(apiFlight(SchDT = dateWithinRange.toISOString(), PcpTime = dateWithinRange.millisSinceEpoch), Set())

        val result = filterCrunchStateByRange(dateWithinRange, range, CrunchState(Set(flightWithinRange), Set(crunchMinuteWithinRange), Set(staffMinuteWithinRange)))
        val expected = CrunchState(Set(flightWithinRange), Set(crunchMinuteWithinRange), Set(staffMinuteWithinRange))

        assert(result == expected)
      }

      "When a CrunchState contains nothing within the range then it should have empty sets for all values" - {
        val crunchMinuteNotWithinRange = CrunchMinute("T1", Queues.EeaDesk, dateOutsideRange.millisSinceEpoch, 0, 0, 0, 0)
        val staffMinuteNotWithinRange = StaffMinute("T1", dateOutsideRange.millisSinceEpoch, 0, 0, 0)
        val flightNotWithinRange = ApiFlightWithSplits(apiFlight(SchDT = dateOutsideRange.toISOString(), PcpTime = dateOutsideRange.millisSinceEpoch), Set())

        val result = filterCrunchStateByRange(dateWithinRange, range, CrunchState(Set(flightNotWithinRange), Set(crunchMinuteNotWithinRange), Set(staffMinuteNotWithinRange)))

        val expected = CrunchState(Set(),Set(),Set())

        assert(result == expected)
      }

      "When a CrunchState contains some minutes within the range and some without it should retain the ones within range" - {
        val crunchMinuteWithinRange = CrunchMinute("T1", Queues.EeaDesk, dateWithinRange.millisSinceEpoch, 0, 0, 0, 0)
        val staffMinuteWithinRange = StaffMinute("T1", dateWithinRange.millisSinceEpoch, 0, 0, 0)
        val flightWithinRange = ApiFlightWithSplits(apiFlight(SchDT = dateWithinRange.toISOString(), PcpTime = dateWithinRange.millisSinceEpoch), Set())

        val crunchMinuteNotWithinRange = CrunchMinute("T1", Queues.EeaDesk, dateOutsideRange.millisSinceEpoch, 0, 0, 0, 0)
        val staffMinuteNotWithinRange = StaffMinute("T1", dateOutsideRange.millisSinceEpoch, 0, 0, 0)
        val flightNotWithinRange = ApiFlightWithSplits(apiFlight(SchDT = dateOutsideRange.toISOString(), PcpTime = dateOutsideRange.millisSinceEpoch), Set())

        val result = filterCrunchStateByRange(dateWithinRange, range, CrunchState(
                  Set(flightNotWithinRange, flightWithinRange),
                  Set(crunchMinuteNotWithinRange, crunchMinuteWithinRange),
                  Set(staffMinuteNotWithinRange, staffMinuteWithinRange)))

        val expected = CrunchState(Set(flightWithinRange), Set(crunchMinuteWithinRange), Set(staffMinuteWithinRange))

        assert(result == expected)
      }
    }
  }

  def mkMillis(t: String): Long = SDate.parse(t).millisSinceEpoch
}
