package drt.client.components

import drt.client.components.TerminalComponent.viewStartAndEnd
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.StaffMinute
import drt.shared.PortState
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource, LiveFeedSource, Queues}
import utest.{TestSuite, _}

object FilterCrunchByRangeTests extends TestSuite {

  val paxFeedSourceOrder: List[FeedSource] = List(ApiFeedSource, LiveFeedSource)

  def tests: Tests = Tests {
    test("Given an hour range of 10 to 14") - {
      val range = CustomWindow("10:00", "14:00")
      val dateWithinRange = SDate("2017-01-01T11:00:00Z")
      val dateOutsideRange = SDate("2017-01-01T09:00:00Z")
      val arrival = ArrivalGenerator.live(terminal = T1, schDt = dateWithinRange.toISOString).toArrival(LiveFeedSource)

      test("When a PortState contains minutes within the range, then they should remain after the filter") - {
        val crunchMinuteWithinRange = CrunchMinute(T1, Queues.EeaDesk, dateWithinRange.millisSinceEpoch, 0, 0, 0, 0, None)
        val staffMinuteWithinRange = StaffMinute(T1, dateWithinRange.millisSinceEpoch, 0, 0, 0)
        val flightWithinRange = ApiFlightWithSplits(arrival.copy(PcpTime = Option(dateWithinRange.millisSinceEpoch)), Set())

        val state = PortState(List(flightWithinRange), List(crunchMinuteWithinRange), List(staffMinuteWithinRange))
        val (start, end) = viewStartAndEnd(dateWithinRange.toLocalDate, range)
        val result = state.window(start, end, paxFeedSourceOrder)
        val expected = PortState(List(flightWithinRange), List(crunchMinuteWithinRange), List(staffMinuteWithinRange))

        assert(result == expected)
      }

      test("When a PortState contains nothing within the range then it should have empty sets for all values") - {
        val crunchMinuteNotWithinRange = CrunchMinute(T1, Queues.EeaDesk, dateOutsideRange.millisSinceEpoch, 0, 0, 0, 0, None)
        val staffMinuteNotWithinRange = StaffMinute(T1, dateOutsideRange.millisSinceEpoch, 0, 0, 0)
        val flightNotWithinRange = ApiFlightWithSplits(arrival.copy(Scheduled = dateOutsideRange.millisSinceEpoch, PcpTime = Option(dateOutsideRange.millisSinceEpoch)), Set())

        val state = PortState(List(flightNotWithinRange), List(crunchMinuteNotWithinRange), List(staffMinuteNotWithinRange))
        val (start, end) = viewStartAndEnd(dateWithinRange.toLocalDate, range)
        val result = state.window(start, end, paxFeedSourceOrder)
        val expected = PortState.empty

        assert(result == expected)
      }

      test("When a PortState contains some minutes within the range and some without it should retain the ones within range") - {
        val crunchMinuteWithinRange = CrunchMinute(T1, Queues.EeaDesk, dateWithinRange.millisSinceEpoch, 0, 0, 0, 0, None)
        val staffMinuteWithinRange = StaffMinute(T1, dateWithinRange.millisSinceEpoch, 0, 0, 0)
        val flightWithinRange = ApiFlightWithSplits(arrival.copy(PcpTime = Option(dateWithinRange.millisSinceEpoch)), Set())

        val crunchMinuteNotWithinRange = CrunchMinute(T1, Queues.EeaDesk, dateOutsideRange.millisSinceEpoch, 0, 0, 0, 0, None)
        val staffMinuteNotWithinRange = StaffMinute(T1, dateOutsideRange.millisSinceEpoch, 0, 0, 0)
        val flightNotWithinRange = ApiFlightWithSplits(arrival.copy(PcpTime = Option(dateOutsideRange.millisSinceEpoch)), Set())

        val portState = PortState(
          List(flightNotWithinRange, flightWithinRange),
          List(crunchMinuteNotWithinRange, crunchMinuteWithinRange),
          List(staffMinuteNotWithinRange, staffMinuteWithinRange))

        val (start, end) = viewStartAndEnd(dateWithinRange.toLocalDate, range)
        val result = portState.window(start, end, paxFeedSourceOrder)

        val expected = PortState(List(flightWithinRange), List(crunchMinuteWithinRange), List(staffMinuteWithinRange))

        assert(result == expected)
      }
    }
  }

  def mkMillis(t: String): Long = SDate(t).millisSinceEpoch
}
