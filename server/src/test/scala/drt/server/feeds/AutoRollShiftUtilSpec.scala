package drt.server.feeds

import drt.shared.{ShiftAssignments, StaffAssignment}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

class AutoRollShiftUtilSpec extends Specification {

  "getShiftAssignmentsForDateRange should create correct slots for 1 hour" >> {
    val start = SDate("2024-07-01T00:00").millisSinceEpoch
    val end = SDate("2024-07-01T01:00").millisSinceEpoch
    val result: ShiftAssignments = AutoRollShiftUtil.getShiftAssignmentsForDateRange(start, end, T1, "Test Shift")

    val expectedSlots = ((end - start) / (ShiftAssignments.periodLengthMinutes * 60 * 1000L)).toInt

    result.assignments.size must beEqualTo(expectedSlots)
    result.assignments.forall(_.terminal == T1) must beTrue
    result == ShiftAssignments(Seq(
      StaffAssignment("Test Shift", T1, SDate("2024-07-01T00:00").millisSinceEpoch, SDate("2024-07-01T00:14").millisSinceEpoch, 0, None),
      StaffAssignment("Test Shift", T1, SDate("2024-07-01T00:15").millisSinceEpoch, SDate("2024-07-01T00:29").millisSinceEpoch, 0, None),
      StaffAssignment("Test Shift", T1, SDate("2024-07-01T00:30").millisSinceEpoch, SDate("2024-07-01T00:44").millisSinceEpoch, 0, None),
      StaffAssignment("Test Shift", T1, SDate("2024-07-01T00:45").millisSinceEpoch, SDate("2024-07-01T00:59").millisSinceEpoch, 0, None)
    ))
  }

  "sixthMonthStartAndEnd should return correct millis for the 6th month from viewDate" >> {
    val viewDate = SDate("2025-09-15T12:00")
    val (startMillis, endMillis) = AutoRollShiftUtil.sixthMonthStartAndEnd(viewDate)

    val expectedStart = SDate("2026-03-01T00:00").millisSinceEpoch
    val expectedEnd = SDate("2026-03-31T23:59").millisSinceEpoch

    startMillis must beEqualTo(expectedStart)
    endMillis must beEqualTo(expectedEnd)
  }

}
