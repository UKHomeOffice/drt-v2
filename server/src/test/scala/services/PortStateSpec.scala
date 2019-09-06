package services

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, PortState, StaffMinute}
import drt.shared._
import org.specs2.mutable.Specification

class PortStateSpec extends Specification {
  "Given a PortState with no purgable entries " +
    "When I purge " +
    "Then I should still see all the entries" >> {
    val newerTime1 = SDate("2019-01-01T00:30Z")
    val newerTime2 = SDate("2019-01-02T00:30Z")
    val oldArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = newerTime1.toISOString())
    val newerArrival = ArrivalGenerator.arrival(iata = "BA0002", schDt = newerTime2.toISOString())
    val flights = arrivalsToFlightsWithSplits(List(oldArrival, newerArrival))

    val newerCrunchMinute1 = CrunchMinute("T1", Queues.EeaDesk, newerTime1.millisSinceEpoch, 0, 0, 0, 0)
    val newerCrunchMinute2 = CrunchMinute("T1", Queues.EeaDesk, newerTime2.millisSinceEpoch, 0, 0, 0, 0)
    val crunchMinutes = List(newerCrunchMinute1, newerCrunchMinute2)

    val newerStaffMinute1 = StaffMinute("T1", newerTime1.millisSinceEpoch, 0, 0, 0)
    val newerStaffMinute2 = StaffMinute("T1", newerTime2.millisSinceEpoch, 0, 0, 0)
    val staffMinutes = List(newerStaffMinute1, newerStaffMinute2)

    val portState = PortState(flights, crunchMinutes, staffMinutes)

    val result = portState.purgeOlderThanDate(newerTime1.millisSinceEpoch)

    val expected = PortState(flights, crunchMinutes, staffMinutes)

    result === expected
  }

  "Given a PortState with only older entries " +
    "When I purge older entries " +
    "Then I should not see any entries survive" >> {
    val oldTime1 = SDate("2019-01-01T00:30Z")
    val oldTime2 = SDate("2019-01-02T00:30Z")
    val oldArrival1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = oldTime1.toISOString())
    val oldArrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = oldTime2.toISOString())
    val flights = arrivalsToFlightsWithSplits(List(oldArrival1, oldArrival2))

    val oldCrunchMinute1 = CrunchMinute("T1", Queues.EeaDesk, oldTime1.millisSinceEpoch, 0, 0, 0, 0)
    val oldCrunchMinute2 = CrunchMinute("T1", Queues.EeaDesk, oldTime2.millisSinceEpoch, 0, 0, 0, 0)
    val crunchMinutes = List(oldCrunchMinute1, oldCrunchMinute2)

    val oldStaffMinute1 = StaffMinute("T1", oldTime1.millisSinceEpoch, 0, 0, 0)
    val oldStaffMinute2 = StaffMinute("T1", oldTime2.millisSinceEpoch, 0, 0, 0)
    val staffMinutes = List(oldStaffMinute1, oldStaffMinute2)

    val portState = PortState(flights, crunchMinutes, staffMinutes)

    val result = portState.purgeOlderThanDate(oldTime2.addMinutes(1).millisSinceEpoch)

    val expected = PortState(arrivalsToFlightsWithSplits(List()), List(), List())

    result === expected
  }

  "Given a PortState with older and newer entries " +
    "When I purge older entries " +
    "Then I should only see the newer entries survive" >> {
    val oldTime = SDate("2019-01-01T00:30Z")
    val newerTime = SDate("2019-01-02T00:30Z")
    val oldArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = oldTime.toISOString())
    val newerArrival = ArrivalGenerator.arrival(iata = "BA0002", schDt = newerTime.toISOString())
    val flights = arrivalsToFlightsWithSplits(List(oldArrival, newerArrival))

    val oldCrunchMinute = CrunchMinute("T1", Queues.EeaDesk, oldTime.millisSinceEpoch, 0, 0, 0, 0)
    val newerCrunchMinute = CrunchMinute("T1", Queues.EeaDesk, newerTime.millisSinceEpoch, 0, 0, 0, 0)
    val crunchMinutes = List(oldCrunchMinute, newerCrunchMinute)

    val oldStaffMinute = StaffMinute("T1", oldTime.millisSinceEpoch, 0, 0, 0)
    val newerStaffMinute = StaffMinute("T1", newerTime.millisSinceEpoch, 0, 0, 0)
    val staffMinutes = List(oldStaffMinute, newerStaffMinute)

    val portState = PortState(flights, crunchMinutes, staffMinutes)

    val result = portState.purgeOlderThanDate(newerTime.millisSinceEpoch)

    val expected = PortState(arrivalsToFlightsWithSplits(List(newerArrival)), List(newerCrunchMinute), List(newerStaffMinute))

    result === expected
  }

  "Given 3 days of crunch minutes across 2 terminals and 2 queues " +
    "When I ask for the middle day's data " +
    "I should not see any data from the days either side" >> {
    val terminalQueues = Map("T1" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk), "T2" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk))
    val threeDayMillis = SDate("2019-01-01").millisSinceEpoch until SDate("2019-01-04").millisSinceEpoch by 60000
    val oneDayMillis = SDate("2019-01-02").millisSinceEpoch until SDate("2019-01-03").millisSinceEpoch by 60000

    val cms = for {
      (terminal, queues) <- terminalQueues
      queue <- queues
      minute <- threeDayMillis
    } yield CrunchMinute(terminal, queue, minute, 5, 10, 2, 15)

    val sms = for {
      terminal <- terminalQueues.keys
      minute <- threeDayMillis
    } yield StaffMinute(terminal, minute, 10, 2, -1)

    val ps = PortState(List(), cms.toList, sms.toList)

    val result = ps.window(SDate("2019-01-02"), SDate("2019-01-03"), terminalQueues)

    val expectedCms = for {
      (terminal, queues) <- terminalQueues
      queue <- queues
      minute <- oneDayMillis
    } yield CrunchMinute(terminal, queue, minute, 5, 10, 2, 15)

    val expectedSms = for {
      terminal <- terminalQueues.keys
      minute <- oneDayMillis
    } yield StaffMinute(terminal, minute, 10, 2, -1)

    val expected = PortState(List(), expectedCms.toList, expectedSms.toList)

    result === expected
  }
  
  private def arrivalsToFlightsWithSplits(arrivals: List[Arrival]): List[ApiFlightWithSplits] = {
    arrivals.map(a => ApiFlightWithSplits(a, Set()))
  }
}
