package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared.{SDateLike, StaffTimeSlot, StaffTimeSlotsForMonth}
import utest._

object StaffingComponentTests extends TestSuite {

  import drt.client.components.TerminalStaffingV2._

  def tests = TestSuite {
    'StaffingService - {
      "When asking for the end date of the month " - {
        "Given 31-12-2017 then I should get 31-12-2017" - {
          val today = SDate(2017, 12, 31)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2017, 12, 31)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given 01-12-2017 then I should get 31-12-2017" - {
          val today = SDate(2017, 12, 1)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2017, 12, 31)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST Date 01-06-2018 then I should get 30-06-2018" - {
          val today = SDate(2018, 6, 1)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2018, 6, 30)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST date 30-06-2018 then I should get 30-06-2018" - {
          val today = SDate(2018, 6, 30)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2018, 6, 30)
          assert(result.toISOString() == expected.toISOString())
        }
      }
      "When asking for the first date of the month " - {
        "Given 31-12-2017 then I should get 01-12-2017" - {
          val today = SDate(2017, 12, 31)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2017, 12, 1)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given 01-12-2017 then I should get 1-12-2017" - {
          val today = SDate(2017, 12, 1)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2017, 12, 1)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST Date 01-06-2018 then I should get 01-06-2018" - {
          val today = SDate(2018, 6, 1)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2018, 6, 1)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST date 30-06-2018 then I should get 01-06-2018" - {
          val today = SDate(2018, 6, 30)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2018, 6, 1)
          assert(result.toISOString() == expected.toISOString())
        }
      }
      "When asking for a list of time slots" - {
        "Given a start time of 2017-12-21T00:00 and an end time of 2017-12-21T01:00 " +
          "Then I should get back 15 minute slots" - {

          val startTime = SDate("2017-12-21T00:00")
          val endTime = SDate("2017-12-21T01:00")

          val result = toTimeSlots(startTime, endTime).map(_.millisSinceEpoch)

          val expected = List(
            SDate("2017-12-21T00:00"),
            SDate("2017-12-21T00:15"),
            SDate("2017-12-21T00:30"),
            SDate("2017-12-21T00:45")
          ).map(_.millisSinceEpoch)

          assert(result == expected)
        }
      }
      "When asking for a list of days" - {
        "Given a start day of 2017-12-21 and an end day of 2017-12-25 " +
          "Then I should get back a list of days in between" - {

          val startDay = SDate("2017-12-21")
          val endDay = SDate("2017-12-25")

          val result = consecutiveDaysInMonth(startDay, endDay).map(_.millisSinceEpoch)

          val expected = List(
            SDate("2017-12-21"),
            SDate("2017-12-22"),
            SDate("2017-12-23"),
            SDate("2017-12-24"),
            SDate("2017-12-25")
          ).map(_.millisSinceEpoch)

          assert(result == expected)
        }
      }
    }
    "When asking for 6 months from first day of month provided" - {
      "Given 2017-06-22 then I should get back 2017-06-01, 2017-07-01, 2017-08-01, 2017-09-01," +
        " 2017-10-01, 2017-11-01," - {
        val startDate = SDate("2017-06-22")

        val expected = List(
          SDate("2017-06-01"),
          SDate("2017-07-01"),
          SDate("2017-08-01"),
          SDate("2017-09-01"),
          SDate("2017-10-01"),
          SDate("2017-11-01")
        )

        val result = sixMonthsFromFirstOfMonth(startDate)

        assert(result.map(_.ddMMyyString) == expected.map(_.ddMMyyString))
      }
      "Given 2017-12-22 then I should get back 2017-12-01, 2018-01-01, 2018-02-01, 2018-03-01," +
        " 2018-04-01, 2018-05-01" - {
        val startDate = SDate("2017-12-22")

        val expected = List(
          SDate("2017-12-01"),
          SDate("2018-01-01"),
          SDate("2018-02-01"),
          SDate("2018-03-01"),
          SDate("2018-04-01"),
          SDate("2018-05-01")
        )

        val result = sixMonthsFromFirstOfMonth(startDate)

        assert(result.map(_.ddMMyyString) == expected.map(_.ddMMyyString))
      }
    }
    "When converting a table of staff per timeslot day to shifts" - {
      "Given one day with 4 time slots then I should get back a list of sfaff timeslots" - {
        val staff = List(
          List(1),
          List(1),
          List(1),
          List(1)
        )

        val start = SDate("2017-12-24")

        val terminal = "T1"

        val result = staffToStaffTimeSlotsForMonth(start, staff, start, terminal)

        val expected = StaffTimeSlotsForMonth(
          start, List(
          StaffTimeSlot("T1", start.millisSinceEpoch, 1),
          StaffTimeSlot("T1", start.addMinutes(15).millisSinceEpoch, 1),
          StaffTimeSlot("T1", start.addMinutes(30).millisSinceEpoch, 1),
          StaffTimeSlot("T1", start.addMinutes(45).millisSinceEpoch, 1)
        ))

        assert(result == expected)
      }
      "Given two days with 4 time slots then I should get back a list of sfaff timeslots" - {
        val staff = List(
          List(1,2),
          List(1,2),
          List(1,2),
          List(1,2)
        )

        val start = SDate("2017-12-24")

        val terminal = "T1"

        val result = staffToStaffTimeSlotsForMonth(start, staff, start, terminal)

        val expected = StaffTimeSlotsForMonth(
          start, List(
          StaffTimeSlot("T1", start.millisSinceEpoch, 1),
          StaffTimeSlot("T1", start.addMinutes(15).millisSinceEpoch, 1),
          StaffTimeSlot("T1", start.addMinutes(30).millisSinceEpoch, 1),
          StaffTimeSlot("T1", start.addMinutes(45).millisSinceEpoch, 1),
          StaffTimeSlot("T1", start.addDays(1).millisSinceEpoch, 2),
          StaffTimeSlot("T1", start.addDays(1).addMinutes(15).millisSinceEpoch, 2),
          StaffTimeSlot("T1", start.addDays(1).addMinutes(30).millisSinceEpoch, 2),
          StaffTimeSlot("T1", start.addDays(1).addMinutes(45).millisSinceEpoch, 2)
        ))

        assert(result == expected)
      }
    }
  }
}
