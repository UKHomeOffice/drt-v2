package drt.client.components

import drt.client.components.StaffingUtil.dateRangeDays
import drt.client.services.JSDateConversions.SDate
import utest._

object StaffingUtilSpec extends TestSuite {

  def tests = Tests {
    test("When StaffingUtilSpec") {
      test("Given consecutiveDayForWeek") {
        test("Then handle the date 2024-10-30 correctly") {
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

      test("Given consecutiveDay") {
        test("Then handle the date 2024-10-30 correctly") {
          val startDay = SDate("2024-10-30T00:00:00Z")
          val result = dateRangeDays(startDay, 1)

          assert(result == Seq(
            (SDate("2024-10-30T00:00:00Z"), "Wednesday")
          ))
        }
      }
    }
    test("Given navigationDates") {
      test("When isWeekly is true") {
        test("Then handle the date 2024-10-30 correctly for weekly view") {
          val viewingDate = SDate("2024-10-30T00:00:00Z")

          val (previousWeekDate, nextWeekDate) =
            StaffingUtil.navigationDates(viewingDate, "weekly", () => SDate("2024-10-30T00:00:00Z"))
          assert(previousWeekDate.millisSinceEpoch == SDate("2024-10-22T23:00:00.00Z").millisSinceEpoch)
          assert(nextWeekDate.millisSinceEpoch == SDate("2024-11-06T00:00:00Z").millisSinceEpoch)
        }

        test("Then handle the date 2024-03-29 correctly when view date is in 6th month ahead for weekly view") {
          val viewingDate = SDate("2025-03-29T23:00:00Z")

          val (previousWeekDate, nextWeekDate) =
            StaffingUtil.navigationDates(viewingDate, "weekly", () => SDate("2024-10-31T00:00:00Z"))
          assert(previousWeekDate.millisSinceEpoch == SDate("2025-03-22T23:00:00Z").millisSinceEpoch)
          assert(nextWeekDate.toIsoMidnight == SDate("2025-04-05T00:00:00Z").toIsoMidnight)
        }
      }

      test("When isDaily is true") {
        test("Then handle the date 2024-10-30 correctly for daily view") {
          val viewingDate = SDate("2024-10-30T00:00:00Z")

          val (previousDayDate, nextDayDate) =
            StaffingUtil.navigationDates(viewingDate, "daily", () => SDate("2024-10-30T00:00:00Z"))

          assert(previousDayDate == SDate("2024-10-29T00:00:00Z"))
          assert(nextDayDate == SDate("2024-10-31T00:00:00Z"))
        }

        test("Then handle the date 2024-03-29 correctly when view date is in 6th month ahead for daily view") {
          val viewingDate = SDate("2025-03-29T00:00:00Z")

          val (previousWeekDate, nextWeekDate) =
            StaffingUtil.navigationDates(viewingDate, "daily", () => SDate("2024-10-30T00:00:00Z"))

          assert(previousWeekDate.toIsoMidnight == SDate("2025-03-28T00:00:00Z").toIsoMidnight)
          assert(nextWeekDate.toIsoMidnight == SDate("2025-03-30T00:00:00Z").toIsoMidnight)
        }
      }

      test("When neither isWeekly nor isDaily is true") {
        test("Then handle the date 2024-10-01 correctly") {
          val viewingDate = SDate("2024-10-01T00:00:00Z")

          val (previousMonthDate, nextMonthDate) =
            StaffingUtil.navigationDates(viewingDate, "monthly", () => SDate("2024-10-30T00:00:00Z"))
          assert(previousMonthDate == SDate("2024-09-30T23:00:00Z"))
          assert(nextMonthDate == SDate("2024-11-01T01:00:00.00Z"))
        }

        test("Then handle the date 2024-03-01 correctly when view date is in 6th month ahead") {
          val viewingDate = SDate("2025-03-01T00:00:00Z")

          val (previousWeekDate, nextWeekDate) =
            StaffingUtil.navigationDates(viewingDate, "daily", () => SDate("2024-10-30T00:00:00Z"))
          assert(previousWeekDate.toIsoMidnight == SDate("2025-02-28T00:00:00Z").toIsoMidnight)
          assert(nextWeekDate.toIsoMidnight == SDate("2025-03-02T00:00:00Z").toIsoMidnight)
        }
      }
    }
  }
}
