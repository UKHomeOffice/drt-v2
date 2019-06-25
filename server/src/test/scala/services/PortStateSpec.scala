package services

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, PortState, StaffMinute}
import drt.shared._
import org.specs2.mutable.Specification

class PortStateSpec extends Specification {
  "Given a PortState " +
    "When I purge entries older than a certain date " +
    "Then I should only see the newer entries survive" >> {
    val oldTime = SDate("2019-01-01T00:30Z")
    val newerTime = SDate("2019-01-02T00:30Z")
    val oldArrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = oldTime.toISOString())
    val newerArrival = ArrivalGenerator.apiFlight(iata = "BA0002", schDt = newerTime.toISOString())
    val flights = arrivalsToFlightsWithSplits(List(oldArrival, newerArrival))

    val oldCrunchMinute = CrunchMinute("T1", Queues.EeaDesk, oldTime.millisSinceEpoch, 0, 0, 0, 0)
    val newerCrunchMinute = CrunchMinute("T1", Queues.EeaDesk, newerTime.millisSinceEpoch, 0, 0, 0, 0)
    val crunchMinutes = List(oldCrunchMinute, newerCrunchMinute)

    val oldStaffMinute = StaffMinute("T1", oldTime.millisSinceEpoch, 0, 0, 0)
    val newerStaffMinute = StaffMinute("T1", newerTime.millisSinceEpoch, 0, 0, 0)
    val staffMinutes = List(oldStaffMinute, newerStaffMinute)

    val portState = PortState(flights, crunchMinutes, staffMinutes)

    val result = portState.purgeOlderThanDate(newerTime)

    val expected = PortState(arrivalsToFlightsWithSplits(List(newerArrival)), List(newerCrunchMinute), List(newerStaffMinute))

    result === expected
  }

  private def arrivalsToFlightsWithSplits(arrivals: List[Arrival]): List[ApiFlightWithSplits] = {
    arrivals.map(a => ApiFlightWithSplits(a, Set()))
  }
}
