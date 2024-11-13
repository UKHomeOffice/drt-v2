package services.crunch

import drt.shared.CrunchApi.{ForecastTimeSlot, StaffMinute}
import drt.shared.PortState
import services.exports.Forecast
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.time.SDate

class PlanningActualStaffSpec() extends CrunchTestLike {
  sequential
  isolated

  val slot0To14: Int = 0 * 60000
  val slot15To29: Int = 15 * 60000
  val slot30To44: Int = 30 * 60000
  val slot45To59: Int = 45 * 60000

  "Given a set of forecast minutes and staff minutes for all terminals, " >> {
    "When I roll up for week per terminal " >> {
      "Then I should get the lowest number in each 15 minute block relevant to the particular terminal" >> {

        val staffMinutesT1: Set[StaffMinute] = (0 to 59)
          .map(index => StaffMinute(terminal = T1, minute = index * 60000, shifts = 20, fixedPoints = 2, movements = 1, lastUpdated = None)).toSet
        val staffMinutesT2 = staffMinutesT1.map(_.copy(terminal = T2, fixedPoints = 3))

        val crunchMinutesT1: Set[CrunchMinute] = (0 to 59)
          .map(index => CrunchMinute(terminal = T1, queue = Queues.EeaDesk, minute = index * 60000,
                                     lastUpdated = None, paxLoad = 0d, workLoad = 0d, deskRec = 1, waitTime = 0, maybePaxInQueue = None)).toSet
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
