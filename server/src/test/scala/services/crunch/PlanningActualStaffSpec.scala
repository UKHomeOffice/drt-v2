package services.crunch

import drt.shared.CrunchApi.{CrunchMinute, ForecastTimeSlot, MillisSinceEpoch, StaffMinute}
import drt.shared.Terminals.{T1, T2}
import drt.shared._
import services.exports.Forecast
import services.{Optimiser, SDate}

import scala.concurrent.duration._

class PlanningActualStaffSpec() extends CrunchTestLike {
  sequential
  isolated

  val slot0To14: Int = 0 * 60000
  val slot15To29: Int = 15 * 60000
  val slot30To44: Int = 30 * 60000
  val slot45To59: Int = 45 * 60000

  "Given 20 staff on shift" >> {
    "When I ask for 1 day of forecast on 2017-01-02T00:00Z after starting with staff checks turned on" >> {
      "Then I should see the actual staff numbers in the forecast" >> {
        val weekBeginning = "2017-01-02T00:00Z"

        val startDate1 = MilliDate(SDate("2017-01-02T00:00").millisSinceEpoch)
        val endDate1 = MilliDate(SDate("2017-01-02T23:59").millisSinceEpoch)
        val assignment1 = StaffAssignment("shift a", T1, startDate1, endDate1, 20, None)

        val crunch = runCrunchGraph(
          now = () => SDate(weekBeginning).addDays(-1),
          initialShifts = ShiftAssignments(Seq(assignment1)),
          cruncher = Optimiser.crunch,
          checkRequiredStaffUpdatesOnStartup = true
          )

        val expected = List(
          ForecastTimeSlot(SDate("2017-01-02T00:00Z").millisSinceEpoch, 20, 0),
          ForecastTimeSlot(SDate("2017-01-02T00:15Z").millisSinceEpoch, 20, 0),
          ForecastTimeSlot(SDate("2017-01-02T00:30Z").millisSinceEpoch, 20, 0),
          ForecastTimeSlot(SDate("2017-01-02T00:45Z").millisSinceEpoch, 20, 0)
          )

        crunch.portStateTestProbe.fishForMessage(10 seconds) {
          case ps: PortState =>
            val cs = ps.crunchSummary(SDate(startDate1), 4, 15, T1, defaultAirportConfig.queuesByTerminal(T1).toList)
            val ss = ps.staffSummary(SDate(startDate1), 4, 15, T1)
            val weekOf15MinSlots: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = Forecast.rollUpForWeek(cs, ss)
            val firstDayFirstHour = weekOf15MinSlots.getOrElse(SDate("2017-01-02T00:00Z").millisSinceEpoch, Seq()).take(4)

            firstDayFirstHour == expected
        }

        success
      }
    }
  }

  "Given a set of forecast minutes and staff minutes for all terminals, " >> {
    "When I roll up for week per terminal " >> {
      "Then I should get the lowest number in each 15 minute block relevant to the particular terminal" >> {

        val staffMinutesT1: Set[StaffMinute] = (0 to 59)
          .map(index => StaffMinute(terminal = T1, minute = index * 60000, shifts = 20, fixedPoints = 2, movements = 1, lastUpdated = None)).toSet
        val staffMinutesT2 = staffMinutesT1.map(_.copy(terminal = T2, fixedPoints = 3))

        val crunchMinutesT1: Set[CrunchMinute] = (0 to 59)
          .map(index => CrunchMinute(terminal = T1, queue = Queues.EeaDesk, minute = index * 60000,
                                     lastUpdated = None, paxLoad = 0d, workLoad = 0d, deskRec = 1, waitTime = 0)).toSet
        val crunchMinutesT2 = crunchMinutesT1.map(_.copy(terminal = T2, deskRec = 2))

        val ps = PortState(List(), (crunchMinutesT1 ++ crunchMinutesT2).toList, (staffMinutesT1 ++ staffMinutesT2).toList)
        val cs = ps.crunchSummary(SDate(0L), 4, 15, T1, defaultAirportConfig.queuesByTerminal(T1).toList)
        val ss = ps.staffSummary(SDate(0L), 4, 15, T1)

        val result = Forecast.rollUpForWeek(cs, ss).values.head.toSet

        val expected = Set(
          ForecastTimeSlot(0, 20, 3),
          ForecastTimeSlot(15 * 60000, 20, 3),
          ForecastTimeSlot(30 * 60000, 20, 3),
          ForecastTimeSlot(45 * 60000, 20, 3)
          )

        result === expected
      }
    }
  }
}
