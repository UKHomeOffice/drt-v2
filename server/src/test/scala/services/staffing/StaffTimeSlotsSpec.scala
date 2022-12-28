package services.staffing

import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import drt.shared._
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate

class StaffTimeSlotsSpec extends Specification {

  import StaffTimeSlots._

  "When replacing a month of shifts with new timeslots" >> {
    "Given no existing shifts then only the new timeslots should be present in the new shifts" >> {
      val existingShifts = ShiftAssignments.empty
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        T1,
        Seq(StaffTimeSlot(T1, startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expectedStart = SDate("2018-01-02T00:00").millisSinceEpoch
      val expectedEnd = SDate("2018-01-02T00:14").millisSinceEpoch
      val expected = ShiftAssignments(Seq(StaffAssignment("shift0120180", T1, expectedStart, expectedEnd, 1, None)))

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }

    "Given a shift for a previous month the new timeslots should be present in the new shifts as well as the old" >> {
      val start = SDate("2017-12-02T00:00").millisSinceEpoch
      val end = SDate("2017-12-02T00:14").millisSinceEpoch
      val existingShifts = ShiftAssignments(Seq(
        StaffAssignment("shift1220170", T1, start, end, 1, None)
      ))
      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        T1,
        Seq(StaffTimeSlot(T1, startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expectedStart1 = SDate("2017-12-02T00:00").millisSinceEpoch
      val expectedEnd1 = SDate("2017-12-02T00:14").millisSinceEpoch
      val expectedStart2 = SDate("2018-01-02T00:00").millisSinceEpoch
      val expectedEnd2 = SDate("2018-01-02T00:14").millisSinceEpoch
      val expected = Set(
        StaffAssignment("shift1220170", T1, expectedStart1, expectedEnd1, 1, None),
        StaffAssignment("shift0120180", T1, expectedStart2, expectedEnd2, 1, None))

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots).assignments.toSet

      result === expected
    }

    "Given a shift for the same month as the new timeslots, it should be replaced by the new timeslots" >> {
      val start1 = SDate("2018-01-05T00:00").millisSinceEpoch
      val end1 = SDate("2018-01-05T00:14").millisSinceEpoch
      val start2 = SDate("2018-01-06T00:00").millisSinceEpoch
      val end2 = SDate("2018-01-06T00:14").millisSinceEpoch
      val start3 = SDate("2018-01-07T00:00").millisSinceEpoch
      val end3 = SDate("2018-01-07T00:14").millisSinceEpoch
      val existingShifts = ShiftAssignments(Seq(
        StaffAssignment("shift0120180", T1, start1, end1, 10, None),
        StaffAssignment("shift0120180", T1, start2, end2, 10, None),
        StaffAssignment("shift0120180", T1, start3, end3, 10, None)
      ))

      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        T1,
        Seq(StaffTimeSlot(T1, startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expectedStart = SDate("2018-01-02T00:00").millisSinceEpoch
      val expectedEnd = SDate("2018-01-02T00:14").millisSinceEpoch
      val expected = ShiftAssignments(Seq(StaffAssignment("shift0120180", T1, expectedStart, expectedEnd, 1, None)))

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots)

      result === expected
    }

    "Given a shift for the same month as the new timeslots but for a different terminal, it should not be replaced" >> {
      val start1 = SDate("2018-01-05T00:00").millisSinceEpoch
      val end1 = SDate("2018-01-05T00:14").millisSinceEpoch
      val start2 = SDate("2018-01-06T00:00").millisSinceEpoch
      val end2 = SDate("2018-01-06T00:14").millisSinceEpoch
      val start3 = SDate("2018-01-07T00:00").millisSinceEpoch
      val end3 = SDate("2018-01-07T00:14").millisSinceEpoch
      val start4 = SDate("2018-01-07T00:00").millisSinceEpoch
      val end4 = SDate("2018-01-07T00:14").millisSinceEpoch
      val existingShifts = ShiftAssignments(Seq(
        StaffAssignment("shift0120180", T1, start1, end1, 10, None),
        StaffAssignment("shift0120180", T1, start2, end2, 10, None),
        StaffAssignment("shift0120180", T1, start3, end3, 10, None),
        StaffAssignment("shift0120180", T2, start4, end4, 10, None)
      ))

      val startTime = SDate("2018-01-02T00:00")
      val slots = StaffTimeSlotsForTerminalMonth(
        startTime.millisSinceEpoch,
        T1,
        Seq(StaffTimeSlot(T1, startTime.millisSinceEpoch, 1, 15 * 60000))
      )

      val expectedStart1 = SDate("2018-01-07T00:00").millisSinceEpoch
      val expectedEnd1 = SDate("2018-01-07T00:14").millisSinceEpoch
      val expectedStart2 = SDate("2018-01-02T00:00").millisSinceEpoch
      val expectedEnd2 = SDate("2018-01-02T00:14").millisSinceEpoch
      val expected = Set(
        StaffAssignment("shift0120180", T2, expectedStart1, expectedEnd1, 10, None),
        StaffAssignment("shift0120180", T1, expectedStart2, expectedEnd2, 1, None))

      val result = replaceShiftMonthWithTimeSlotsForMonth(existingShifts, slots).assignments.toSet

      result === expected
    }
  }

  "When getting all shifts for a specific month" >> {
    "Given a month for which there is no shifts then the result should be empty" >> {
      val start = SDate("2018-01-05T00:00").millisSinceEpoch
      val end = SDate("2018-01-05T00:14").millisSinceEpoch
      val shifts = ShiftAssignments(Seq(StaffAssignment("shift1220170", T1, start, end, 10, None)))
      val month = SDate("2019-01-02T00:00")

      val expected = ShiftAssignments.empty

      val result = getShiftsForMonth(shifts, month)

      result === expected
    }

    "Given shifts for the month requested then those shifts should be returned" >> {
      val start = SDate("2018-01-05T00:00").millisSinceEpoch
      val end = SDate("2018-01-05T00:14").millisSinceEpoch
      val shifts = ShiftAssignments(Seq(StaffAssignment("shift1220170", T1, start, end, 10, None)))
      val month = SDate("2018-01-02T00:00")

      val expected = shifts

      val result = getShiftsForMonth(shifts, month)

      result === expected
    }

    "Given shifts for both the month requested and another month then only the requested month should be returned" >> {
      val start1 = SDate("2018-01-05T00:00").millisSinceEpoch
      val end1 = SDate("2018-01-05T00:14").millisSinceEpoch
      val start2 = SDate("2018-02-05T00:00").millisSinceEpoch
      val end2 = SDate("2018-02-05T00:14").millisSinceEpoch
      val shifts = ShiftAssignments(Seq(
        StaffAssignment("shift0120180", T1, start1, end1, 10, None),
        StaffAssignment("shift0120180", T1, start2, end2, 10, None)))
      val month = SDate("2018-01-02T00:00")

      val expectedStart = SDate("2018-01-05T00:00").millisSinceEpoch
      val expectedEnd = SDate("2018-01-05T00:14").millisSinceEpoch
      val expected = ShiftAssignments(Seq(
        StaffAssignment("shift0120180", T1, expectedStart, expectedEnd, 10, None)
      ))

      val result = getShiftsForMonth(shifts, month)

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
