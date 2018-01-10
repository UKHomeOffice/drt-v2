package services.shifts

import drt.shared.{SDateLike, StaffTimeSlot, StaffTimeSlotsForTerminalMonth}
import org.specs2.mutable.Specification
import services.SDate

class StaffTimeSlotsSpec extends Specification {

  import StaffTimeSlots._

  "When converting StaffTimeSlotsForTerminalMonth to shifts" >> {
    "Given a StaffTimeSlotsForTerminalMonth with one 15 minute StaffTimeSlot then I should get one matching shift" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = slotsToShifts(slots)

      result === expected
    }

    "Given a StaffTimeSlotsForTerminalMonth with two 15 minute StaffTimeSlotsForTerminalMonth then I should get two matching shifts" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
        Seq(
          StaffTimeSlot("T1", startTime.millisSinceEpoch, 1, 15 * 60000),
          StaffTimeSlot("T1", startTime.addMinutes(15).millisSinceEpoch, 2, 15 * 60000)
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

    "Given a StaffTimeSlotsForTerminalMonth with two 1 hour StaffTimeSlotsForTerminalMonth then I should get two matching shifts" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
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

    "Given a StaffTimeSlotsForTerminalMonth containing entries with zero staff then those entries should be ignored" >> {
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
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

  "When replacing a monthMillis of shifts with new timeslots" >> {
    "Given no existing shifts then only the new timeslots should be present in the new shifts" >> {
      val existingShifts = ""
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }

    "Given a shift for a previous monthMillis the new timeslots should be present in the new shifts as well as the old" >> {
      val existingShifts =
        """
          |shift1220170, T1, 02/12/17, 00:00, 00:14, 1
        """.stripMargin.trim
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expected =
        """
          |shift1220170, T1, 02/12/17, 00:00, 00:14, 1
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }

    "Given a shift for the same monthMillis as the new timeslots, it should be replaced by the new timeslots" >> {
      val existingShifts =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 06/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 07/01/18, 00:00, 00:14, 10
        """.stripMargin.trim
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expected =
        """
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }
    "Given a shift for the same monthMillis as the new timeslots but for a different terminal, it should not be replaced" >> {
      val existingShifts =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 06/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 07/01/18, 00:00, 00:14, 10
          |shift0120180, T2, 07/01/18, 00:00, 00:14, 10
        """.stripMargin.trim
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        "T1",
        Seq(StaffTimeSlot("T1", startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expected =
        """
          |shift0120180, T2, 07/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 02/01/18, 00:00, 00:14, 1
        """.stripMargin.trim

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }
  }

  "When getting all shifts for a specific month" >> {
    "Given a month for which there is no shifts then the result should be empty" >> {
      val shifts =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
        """.stripMargin.trim
      val month = SDate("2019-01-02T00:00")

      val expected = ""

      val result = getShiftsForMonth(shifts, month)

      result === expected
    }

    "Given shifts for the month requested then those shifts should be returned" >> {
      val shifts =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
        """.stripMargin.trim
      val month = SDate("2018-01-02T00:00")

      val expected =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
        """.stripMargin.trim

      val result = getShiftsForMonth(shifts, month)

      result === expected
    }

    "Given shifts for both the month requested and another month then only the requested month should be returned" >> {
      val shifts =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
          |shift0120180, T1, 05/02/18, 00:00, 00:14, 10
        """.stripMargin.trim
      val month = SDate("2018-01-02T00:00")

      val expected =
        """
          |shift0120180, T1, 05/01/18, 00:00, 00:14, 10
        """.stripMargin.trim

      val result = getShiftsForMonth(shifts, month)

      result === expected
    }
  }

  "When checking if a shift string falls within a particular monthMillis" >> {
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
