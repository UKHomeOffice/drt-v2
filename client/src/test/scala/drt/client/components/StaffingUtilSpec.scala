package drt.client.components

import drt.client.components.StaffingUtil.dateRangeDays
import drt.client.services.JSDateConversions.SDate
import utest._

object StaffingUtilSpec extends TestSuite {

  def tests = Tests {
    "When StaffingUtilSpec" - {
      "Given consecutiveDayForWeek" - {
        "Then handle the date 2024-10-30 correctly" - {
          val viewingDate = SDate("2024-10-30T00:00:00Z")
          val result = StaffingUtil.consecutiveDayForWeek(viewingDate)

          assert(result == Seq(
            (SDate("2024-10-28T00:00:00Z"), "Monday"),
            (SDate("2024-10-29T00:00:00Z"), "Tuesday"),
            (SDate("2024-10-30T00:00:00Z"), "Wednesday"),
            (SDate("2024-10-31T00:00:00Z"), "Thursday"),
            (SDate("2024-11-01T00:00:00Z"), "Friday"),
            (SDate("2024-11-02T00:00:00Z"), "Saturday"),
            (SDate("2024-11-03T00:00:00Z"), "Sunday")
          ))
        }
      }

      "Given consecutiveDay" - {
        "Then handle the date 2024-10-30 correctly" - {
          val startDay = SDate("2024-10-30T00:00:00Z")
          val result = dateRangeDays(startDay, 1)

          assert(result == Seq(
            (SDate("2024-10-30T00:00:00Z"), "Wednesday")
          ))
        }
      }
    }
    "Given navigationDates" - {
      "When isWeekly is true" - {
        "Then handle the date 2024-10-30 correctly" - {
          val viewingDate = SDate("2024-10-30T00:00:00Z")
          val isWeekly = true
          val isDaily = false

          val (previousWeekDate, nextWeekDate) = StaffingUtil.navigationDates(viewingDate, isWeekly, isDaily, () => SDate("2024-10-30T00:00:00Z"))
          assert(previousWeekDate.millisSinceEpoch == SDate("2024-10-22T23:00:00.00Z").millisSinceEpoch)
          assert(nextWeekDate.millisSinceEpoch == SDate("2024-11-06T00:00:00Z").millisSinceEpoch)
        }

        "Then handle the date 2024-03-29 correctly when view date is in 6th month ahead" - {
          val viewingDate = SDate("2025-03-29T23:00:00Z")
          val isWeekly = true
          val isDaily = false

          val (previousWeekDate, nextWeekDate) = StaffingUtil.navigationDates(viewingDate, isWeekly, isDaily, () => SDate("2024-10-31T00:00:00Z"))
          assert(previousWeekDate.millisSinceEpoch == SDate("2025-03-22T23:00:00Z").millisSinceEpoch)
          assert(nextWeekDate.toIsoMidnight == SDate("2025-04-05T00:00:00Z").toIsoMidnight)
        }
      }

      "When isDaily is true" - {
        "Then handle the date 2024-10-30 correctly" - {
          val viewingDate = SDate("2024-10-30T00:00:00Z")
          val isWeekly = false
          val isDaily = true

          val (previousDayDate, nextDayDate) = StaffingUtil.navigationDates(viewingDate, isWeekly, isDaily, () => SDate("2024-10-30T00:00:00Z"))

          assert(previousDayDate == SDate("2024-10-29T00:00:00Z"))
          assert(nextDayDate == SDate("2024-10-31T00:00:00Z"))
        }

        "Then handle the date 2024-03-29 correctly when view date is in 6th month ahead" - {
          val viewingDate = SDate("2025-03-29T00:00:00Z")
          val isWeekly = false
          val isDaily = true

          val (previousWeekDate, nextWeekDate) = StaffingUtil.navigationDates(viewingDate, isWeekly, isDaily, () => SDate("2024-10-30T00:00:00Z"))

          assert(previousWeekDate.toIsoMidnight == SDate("2025-03-28T00:00:00Z").toIsoMidnight)
          assert(nextWeekDate.toIsoMidnight == SDate("2025-03-30T00:00:00Z").toIsoMidnight)
        }
      }

      "When neither isWeekly nor isDaily is true" - {
        "Then handle the date 2024-10-01 correctly" - {
          val viewingDate = SDate("2024-10-01T00:00:00Z")
          val isWeekly = false
          val isDaily = false

          val (previousMonthDate, nextMonthDate) = StaffingUtil.navigationDates(viewingDate, isWeekly, isDaily, () => SDate("2024-10-30T00:00:00Z"))
          assert(previousMonthDate == SDate("2024-09-30T23:00:00Z"))
          assert(nextMonthDate == SDate("2024-11-01T01:00:00.00Z"))
        }

        "Then handle the date 2024-03-01 correctly when view date is in 6th month ahead" - {
          val viewingDate = SDate("2025-03-01T00:00:00Z")
          val isWeekly = false
          val isDaily = true

          val (previousWeekDate, nextWeekDate) = StaffingUtil.navigationDates(viewingDate, isWeekly, isDaily, () => SDate("2024-10-30T00:00:00Z"))
          assert(previousWeekDate.toIsoMidnight == SDate("2025-02-28T00:00:00Z").toIsoMidnight)
          assert(nextWeekDate.toIsoMidnight == SDate("2025-03-02T00:00:00Z").toIsoMidnight)
        }
      }
    }
  }
}