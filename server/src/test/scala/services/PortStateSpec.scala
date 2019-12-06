package services

import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared._
import services.crunch.CrunchTestLike
import services.graphstages.{SimulationMinute, SimulationMinutes}

import scala.concurrent.duration._

class PortStateSpec extends CrunchTestLike {
  "Given an initial PortState with some pax loads " +
    "When I pass in some staffing affecting the same date " +
    "I should see the pax loads are unaffected" >> {
    val minute = "2019-01-02T08:00"
    val millis = SDate(minute).millisSinceEpoch
    val cm = CrunchMinute(T1, Queues.EeaDesk, millis, 10, 50, 10, 50)
    val portState = PortState(List(), List(cm), List())

    val crunch = runCrunchGraph(initialPortState = Option(portState), now = () => SDate(minute).addMinutes(-60))

    offerAndWait(crunch.shiftsInput, ShiftAssignments(Seq(StaffAssignment("", T1, MilliDate(SDate(minute).addMinutes(-15).millisSinceEpoch), MilliDate(SDate(minute).addMinutes(15).millisSinceEpoch), 1, None))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case ps: PortState =>
        val staffUpdated = ps.staffMinutes.exists {
          case (TM(T1, m), sm) =>
            m == millis && sm.shifts == 1
        }
        val paxLoadUnchanged = ps.crunchMinutes.exists {
          case (TQM(T1, Queues.EeaDesk, m), cm) =>
            m == millis && cm.paxLoad == 10
        }

        staffUpdated && paxLoadUnchanged
    }
    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a PortState with no purgable entries " +
    "When I purge " +
    "Then I should still see all the entries" >> {
    val newerTime1 = SDate("2019-01-01T00:30Z")
    val newerTime2 = SDate("2019-01-02T00:30Z")
    val oldArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = newerTime1.toISOString())
    val newerArrival = ArrivalGenerator.arrival(iata = "BA0002", schDt = newerTime2.toISOString())
    val flights = arrivalsToFlightsWithSplits(List(oldArrival, newerArrival))

    val newerCrunchMinute1 = CrunchMinute(T1, Queues.EeaDesk, newerTime1.millisSinceEpoch, 0, 0, 0, 0)
    val newerCrunchMinute2 = CrunchMinute(T1, Queues.EeaDesk, newerTime2.millisSinceEpoch, 0, 0, 0, 0)
    val crunchMinutes = List(newerCrunchMinute1, newerCrunchMinute2)

    val newerStaffMinute1 = StaffMinute(T1, newerTime1.millisSinceEpoch, 0, 0, 0)
    val newerStaffMinute2 = StaffMinute(T1, newerTime2.millisSinceEpoch, 0, 0, 0)
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

    val oldCrunchMinute1 = CrunchMinute(T1, Queues.EeaDesk, oldTime1.millisSinceEpoch, 0, 0, 0, 0)
    val oldCrunchMinute2 = CrunchMinute(T1, Queues.EeaDesk, oldTime2.millisSinceEpoch, 0, 0, 0, 0)
    val crunchMinutes = List(oldCrunchMinute1, oldCrunchMinute2)

    val oldStaffMinute1 = StaffMinute(T1, oldTime1.millisSinceEpoch, 0, 0, 0)
    val oldStaffMinute2 = StaffMinute(T1, oldTime2.millisSinceEpoch, 0, 0, 0)
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

    val oldCrunchMinute = CrunchMinute(T1, Queues.EeaDesk, oldTime.millisSinceEpoch, 0, 0, 0, 0)
    val newerCrunchMinute = CrunchMinute(T1, Queues.EeaDesk, newerTime.millisSinceEpoch, 0, 0, 0, 0)
    val crunchMinutes = List(oldCrunchMinute, newerCrunchMinute)

    val oldStaffMinute = StaffMinute(T1, oldTime.millisSinceEpoch, 0, 0, 0)
    val newerStaffMinute = StaffMinute(T1, newerTime.millisSinceEpoch, 0, 0, 0)
    val staffMinutes = List(oldStaffMinute, newerStaffMinute)

    val portState = PortState(flights, crunchMinutes, staffMinutes)

    val result = portState.purgeOlderThanDate(newerTime.millisSinceEpoch)

    val expected = PortState(arrivalsToFlightsWithSplits(List(newerArrival)), List(newerCrunchMinute), List(newerStaffMinute))

    result === expected
  }

  "Given 3 days of crunch minutes across 2 terminals and 2 queues " +
    "When I ask for the middle day's data " +
    "I should not see any data from the days either side" >> {
    val terminalQueues: Map[Terminal, Seq[Queue]] = Map(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk), T2 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk))
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

  "Given a PortState with two crunch minutes " +
    "When I apply a set of DeskRecMinutes containing two minutes, only one of which is different to the PortState's " +
    "Then the PortStateDiff should only contain the one updated CrunchMinute" >> {
    val cm1 = CrunchMinute(T1, Queues.EGate, 0L, 1, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), None)
    val cm2 = CrunchMinute(T1, Queues.EGate, 1L, 2, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), Option(1L))

    val portState = PortStateMutable.empty
    portState.crunchMinutes +++= List(cm1, cm2)

    val dr1 = DeskRecMinute(T1, Queues.EGate, 0L, 1, 2, 3, 4)
    val dr2 = DeskRecMinute(T1, Queues.EGate, 1L, 100, 2, 3, 4)
    val diff = DeskRecMinutes(Seq(dr1, dr2)).applyTo(portState, 10L)

    diff === PortStateDiff(Seq(), Seq(), Seq(), Seq(CrunchMinute(T1, Queues.EGate, 1L, 100, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), Option(10L))), Seq())
  }

  "Given a PortState with two crunch minutes " +
    "When I apply a set of ActualDeskStats containing two entries representing 30 minutes, only one of which is different to the PortState's " +
    "Then the PortStateDiff should only contain the 15 minutes of updated CrunchMinutes" >> {
    val fifteenMins = 15 * 60000L
    val cm1 = fifteenMinsOf(CrunchMinute(T1, Queues.EGate, 0L, 1, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), None))
    val cm2 = fifteenMinsOf(CrunchMinute(T1, Queues.EGate, fifteenMins, 2, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), Option(1L)))
    val portState = PortStateMutable.empty
    portState.crunchMinutes +++= (cm1 ++ cm2)

    val diff = ActualDeskStats(Map(T1 -> Map(Queues.EGate -> Map(
      0L -> DeskStat(Option(7), Option(8)),
      fifteenMins -> DeskStat(Option(100), Option(100))
    )))).applyTo(portState, 10L)

    val expectedCms = fifteenMinsOf(CrunchMinute(T1, Queues.EGate, fifteenMins, 2, 2, 3, 4, Option(5), Option(6), Option(100), Option(100), Option(10L)))

    diff === PortStateDiff(Seq(), Seq(), Seq(), expectedCms, Seq())
  }

  private def fifteenMinsOf(crunchMinute: CrunchMinute): IndexedSeq[CrunchMinute] = {
    (0L until 15L).map(m => crunchMinute.copy(minute = crunchMinute.minute + m * 60000))
  }

  "Given a PortState with two crunch minutes " +
    "When I apply a set of SimulationMinutes containing two minutes, only one of which is different to the PortState's " +
    "Then the PortStateDiff should only contain the one updated CrunchMinute" >> {
    val cm1 = CrunchMinute(T1, Queues.EGate, 0L, 1, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), None)
    val cm2 = CrunchMinute(T1, Queues.EGate, 1L, 2, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), Option(1L))

    val portState = PortStateMutable.empty
    portState.crunchMinutes +++= List(cm1, cm2)

    val diff = SimulationMinutes(Seq(
      SimulationMinute(T1, Queues.EGate, 0L, 5, 6),
      SimulationMinute(T1, Queues.EGate, 1L, 100, 100)
    )).applyTo(portState, 10L)

    diff === PortStateDiff(Seq(), Seq(), Seq(), Seq(CrunchMinute(T1, Queues.EGate, 1L, 2, 2, 3, 4, Option(100), Option(100), Option(7), Option(8), Option(10L))), Seq())
  }

  "Given a PortState with two crunch minutes " +
    "When I apply aset of StaffMinutes containing two minutes, only one of which is different to the PortState's " +
    "Then the PortStateDiff should only contain the one updated StaffMinute" >> {
    val sm1 = StaffMinute(T1, 0L, 1, 2, 3, None)
    val sm2 = StaffMinute(T1, 1L, 2, 2, 3, None)

    val portState = PortStateMutable.empty
    portState.staffMinutes +++= List(sm1, sm2)

    val diff = StaffMinutes(Seq(
      StaffMinute(T1, 0L, 1, 2, 3, None),
      StaffMinute(T1, 1L, 200, 200, 300, None)
    )).applyTo(portState, 10L)

    diff === PortStateDiff(Seq(), Seq(), Seq(), Seq(), Seq(StaffMinute(T1, 1L, 200, 200, 300, Option(10L))))
  }

  "Given a PortState with a flight scheduled before midnight and pax arriving after midnight " +
    "When I ask for a window containing the period immediately after midnight " +
    "Then the flight should be in the returned PortState" >> {
    val portStateMutable = PortStateMutable.empty
    val flight = ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(100), pcpDt = "2019-01-02T00:01"), Set())

    portStateMutable.flights +++= Seq(flight)

    val windowedFlights = portStateMutable.window(SDate("2019-01-02T00:00"), SDate("2019-01-02T12:00")).flights.values.toSet

    windowedFlights === Set(flight)
  }

  "Given a PortState with a flight scheduled after next midnight and pax arriving before next midnight " +
    "When I ask for a window containing the period immediately before midnight " +
    "Then the flight should be in the returned PortState" >> {
    val portStateMutable = PortStateMutable.empty
    val flight = ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-03T12:00", actPax = Option(100), pcpDt = "2019-01-02T14:00"), Set())

    portStateMutable.flights +++= Seq(flight)

    val windowedFlights = portStateMutable.window(SDate("2019-01-02T00:00"), SDate("2019-01-02T23:59")).flights.values.toSet

    windowedFlights === Set(flight)
  }

  private def arrivalsToFlightsWithSplits(arrivals: List[Arrival]): List[ApiFlightWithSplits] = {
    arrivals.map(a => ApiFlightWithSplits(a, Set()))
  }
}
