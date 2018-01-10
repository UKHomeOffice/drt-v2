package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared.{SDateLike, StaffTimeSlot, StaffTimeSlotsForTerminalMonth}

import utest._

import scala.collection.immutable.Seq

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
    "When converting a table of staff per time slot day to shifts" - {
      "Given one day with 4 time slots with 15 minute time slots then I should get back a list of sfaff timeslots" - {
        val staff = List(
          List(1),
          List(1),
          List(1),
          List(1)
        )

        val start = SDate("2017-12-24")

        val terminal = "T1"

        val result = staffToStaffTimeSlotsForMonth(start, staff, terminal, 15)

        val expected = StaffTimeSlotsForTerminalMonth(
          start.millisSinceEpoch, terminal, List(
            StaffTimeSlot("T1", start.millisSinceEpoch, 1, 15 * 60000),
            StaffTimeSlot("T1", start.addMinutes(15).millisSinceEpoch, 1, 15 * 60000),
            StaffTimeSlot("T1", start.addMinutes(30).millisSinceEpoch, 1, 15 * 60000),
            StaffTimeSlot("T1", start.addMinutes(45).millisSinceEpoch, 1, 15 * 60000)
          ))

        assert(result == expected)
      }
      "Given two days with 4 time slots with 15 minute time slots then I should get back a list of sfaff timeslots" - {
        val staff = List(
          List(1, 2),
          List(1, 2),
          List(1, 2),
          List(1, 2)
        )

        val start = SDate("2017-12-24")

        val terminal = "T1"

        val result = staffToStaffTimeSlotsForMonth(start, staff, terminal, 15)

        val expected = StaffTimeSlotsForTerminalMonth(
          start.millisSinceEpoch, terminal, List(
            StaffTimeSlot("T1", start.millisSinceEpoch, 1, 15 * 60000),
            StaffTimeSlot("T1", start.addMinutes(15).millisSinceEpoch, 1, 15 * 60000),
            StaffTimeSlot("T1", start.addMinutes(30).millisSinceEpoch, 1, 15 * 60000),
            StaffTimeSlot("T1", start.addMinutes(45).millisSinceEpoch, 1, 15 * 60000),
            StaffTimeSlot("T1", start.addDays(1).millisSinceEpoch, 2, 15 * 60000),
            StaffTimeSlot("T1", start.addDays(1).addMinutes(15).millisSinceEpoch, 2, 15 * 60000),
            StaffTimeSlot("T1", start.addDays(1).addMinutes(30).millisSinceEpoch, 2, 15 * 60000),
            StaffTimeSlot("T1", start.addDays(1).addMinutes(45).millisSinceEpoch, 2, 15 * 60000)
          ))

        assert(result == expected)
      }
      "Given two days with 4 time slots with 60 minute time slots then I should get back a list of sfaff timeslots" - {
        val staff = List(
          List(1, 2),
          List(1, 2),
          List(1, 2),
          List(1, 2)
        )

        val start = SDate("2017-12-24")

        val terminal = "T1"

        val result = staffToStaffTimeSlotsForMonth(start, staff, terminal, 60)

        val expected = StaffTimeSlotsForTerminalMonth(
          start.millisSinceEpoch, terminal, List(
            StaffTimeSlot("T1", start.millisSinceEpoch, 1, 60 * 60000),
            StaffTimeSlot("T1", start.addMinutes(60).millisSinceEpoch, 1, 60 * 60000),
            StaffTimeSlot("T1", start.addMinutes(120).millisSinceEpoch, 1, 60 * 60000),
            StaffTimeSlot("T1", start.addMinutes(180).millisSinceEpoch, 1, 60 * 60000),
            StaffTimeSlot("T1", start.addDays(1).millisSinceEpoch, 2, 60 * 60000),
            StaffTimeSlot("T1", start.addDays(1).addMinutes(60).millisSinceEpoch, 2, 60 * 60000),
            StaffTimeSlot("T1", start.addDays(1).addMinutes(120).millisSinceEpoch, 2, 60 * 60000),
            StaffTimeSlot("T1", start.addDays(1).addMinutes(180).millisSinceEpoch, 2, 60 * 60000)
          )
        )

        assert(result == expected)
      }
    }

    "When applying changes to a list of staff per timeslot" - {
      import scala.collection.immutable.Seq
      "Given 1 day with 1 time slot with 1 staff member and no changes then the time slot should be unchanged" - {
        val staffTimeSlotDays = Seq(Seq(1))
        val changes = Map[String, Int]()

        val result = applyRecordedChangesToShiftState(staffTimeSlotDays, changes)
        val expected = Seq(Seq(1))

        assert(result == expected)
      }


      "Given 1 day with 1 time slot with 1 staff member and 1 change with 2 staff then the timeslot should contain 2 staff" - {
        val staffTimeSlotDays = Seq(Seq(1))
        val changes = Map(TimeSlotDay(0, 0).key -> 2)

        val result = applyRecordedChangesToShiftState(staffTimeSlotDays, changes)
        val expected = Seq(Seq(2))

        assert(result == expected)
      }

      "Given 1 day with 10 time slot with 1 staff member and 2 changes with 2 staff then changes should be reflected" - {
        val staffTimeSlotDays = Seq(Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1))
        val changes = Map(
          TimeSlotDay(0, 0).key -> 2,
          TimeSlotDay(1, 0).key -> 2
        )

        val result = applyRecordedChangesToShiftState(staffTimeSlotDays, changes)
        val expected = Seq(Seq(2), Seq(2), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1), Seq(1))

        assert(result == expected)
      }

      "Given 2 daya with 10 time slot with 1 staff member and 4 changes with 2 staff then changes should be reflected" - {
        val staffTimeSlotDays = Seq(
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1)
        )
        val changes = Map(
          TimeSlotDay(0, 0).key -> 2,
          TimeSlotDay(1, 0).key -> 2,
          TimeSlotDay(0, 1).key -> 2,
          TimeSlotDay(1, 1).key -> 2
        )

        val result = applyRecordedChangesToShiftState(staffTimeSlotDays, changes)
        val expected = Seq(
          Seq(2, 2),
          Seq(2, 2),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1),
          Seq(1, 1)
        )

        assert(result == expected)
      }
    }

    "When producing staff timeslots for a day" - {
      "Given 2018-01-10 and a time slot length of 15 minutes " +
        "then I should get back 96 fifteen minute time slots starting at midnight" - {
        val startDate = SDate("2018-01-10")
        val slotDuration = 15

        val result = slotsInDay(startDate, slotDuration)

        val expected = List.tabulate(96)(i => startDate.addMinutes(i * 15))

        assert(result == expected)
      }
      "Given 2018-01-10 and a time slot length of 60 minutes " +
        "then I should get back 24 sixty minute time slots starting at midnight" - {
        val startDate = SDate("2018-01-10")
        val slotDuration = 60

        val result = slotsInDay(startDate, slotDuration)

        val expected = List.tabulate(24)(i => startDate.addMinutes(i * 60))

        assert(result == expected)
      }
    }
  }
}
