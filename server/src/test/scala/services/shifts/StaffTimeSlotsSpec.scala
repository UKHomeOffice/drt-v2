package services.shifts

import drt.shared.{StaffTimeSlot, StaffTimeSlotsForMonth}
import org.specs2.mutable.Specification
import services.SDate

class StaffTimeSlotsSpec extends Specification {

  import StaffTimeSlots._

  "When converting StaffTimeSlotsForMonth to shifts" >> {
    "Given a StaffTimeSlotsForMonth with one 15 minute StaffTimeSlot then I should get one matching shift" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForMonth(
        startTime,
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1))
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = slotsToShifts(slots)

      result === expected
    }

    "Given a StaffTimeSlotsForMonth with two 15 minute StaffTimeSlotsForMonth then I should get two matching shifts" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForMonth(
        startTime,
        Seq(
          StaffTimeSlot("T1", startTime.millisSinceEpoch, 1),
          StaffTimeSlot("T1", startTime.addMinutes(15).millisSinceEpoch, 2)
        )
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
          |shift0120181, T1, 02/01/18, 00:15, 00:29, 2
        """.stripMargin.trim

      val result = slotsToShifts(slots)

      result === expected
    }

    "Given a StaffTimeSlotsForMonth with two 1 hour StaffTimeSlotsForMonth then I should get two matching shifts" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForMonth(
        startTime,
        Seq(
          StaffTimeSlot("T1", startTime.millisSinceEpoch, 1, 60 * 60000),
          StaffTimeSlot("T1", startTime.addMinutes(60).millisSinceEpoch, 2, 60 * 60000)
        )
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:59, 1
          |shift0120181, T1, 02/01/18, 01:00, 01:59, 2
        """.stripMargin.trim

      val result = slotsToShifts(slots)

      result === expected
    }

    "Given a StaffTimeSlotsForMonth containing entries with zero staff then those entries should be ignored" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForMonth(
        startTime,
        Seq(
          StaffTimeSlot("T1", startTime.millisSinceEpoch, 0, 60 * 60000),
          StaffTimeSlot("T1", startTime.addMinutes(60).millisSinceEpoch, 0, 60 * 60000)
        )
      )

      val expected = ""

      val result = slotsToShifts(slots)

      result === expected
    }
  }

  "When replacing a month of shifts with new timeslots" >> {
    "Given no existing shifts then only the new timeslots should be present in the new shifts" >> {
      val existingShifts = ""
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForMonth(
        startTime,
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1))
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }

    "Given a shift for a previous month the new timeslots should be present in the new shifts as well as the old" >> {
      val existingShifts =
        """
          |shift1220170, T1, 02/12/17, 00:00, 00:14, 1
        """.stripMargin.trim
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForMonth(
        startTime,
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1))
      )

      val expected =
        """
          |shift1220170, T1, 02/12/17, 00:00, 00:14, 1
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }

    "Given a shift for the same month as the new timeslots, it should be replaced by the new timeslots" >> {
      val existingShifts =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 06/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 07/01/18, 00:00, 00:14, 10
        """.stripMargin.trim
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForMonth(
        startTime,
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1))
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }
  }

  "When checking if a shift string falls within a particular month" >> {
    "Given 01/01/2018 and SDate(2018, 1, 1) then the result should be true" >> {
      val dateString = "01/01/2018"
      val month = SDate(2018, 1, 1, 0, 0)

      val result = isDateInMonth(dateString, month)
      val expected = true

      result === expected
    }

    "Given 01/02/2018 and SDate(2018, 1, 1) then the result should be false" >> {
      val dateString = "01/02/2018"
      val month = SDate(2018, 1, 1, 0, 0)

      val result = isDateInMonth(dateString, month)
      val expected = false

      result === expected
    }

    "Given 01/01/18 and SDate(2018, 1, 1) then the result should be true" >> {
      val dateString = "01/01/18"
      val month = SDate(2018, 1, 1, 0, 0)

      val result = isDateInMonth(dateString, month)
      val expected = true

      result === expected
    }
  }
}
