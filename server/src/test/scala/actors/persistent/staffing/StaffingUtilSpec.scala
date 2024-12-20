package actors.persistent.staffing

import drt.shared.{ShiftAssignments, StaffAssignment, StaffShift}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

class StaffingUtilSpec extends Specification {

  "StaffingUtil" should {
    "generate daily assignments for each day between start and end date" in {
      val shift = StaffShift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Morning Shift",
        startDate = LocalDate(2023, 10, 1),
        startTime = "08:00",
        endTime = "16:00",
        endDate = Some(LocalDate(2023, 10, 3)),
        staffNumber = 5,
        frequency = None,
        createdBy = Some("test"),
        createdAt = System.currentTimeMillis()
      )

      val assignments: Seq[StaffAssignment] = StaffingUtil.generateDailyAssignments(shift)

      assignments.length must beEqualTo(3)

      assignments.foreach { assignment =>
        assignment.name must beEqualTo("Morning Shift")
        assignment.terminal.toString must beEqualTo("T1")
        assignment.numberOfStaff must beEqualTo(5)
        assignment.createdBy must beSome("test")
      }

      val expectedStartMillis = SDate(2023, 10, 1, 8, 0).millisSinceEpoch

      val expectedEndMillis = SDate(2023, 10, 1, 16, 0).millisSinceEpoch

      assignments.head.start must beEqualTo(expectedStartMillis)
      assignments.last.end must beEqualTo(expectedEndMillis)
    }
  }

  "updateWithDefaultShift" should {
    "update assignments with zero staff" in {
      val shifts = Seq(
        StaffShift("LHR", "T1", "day", LocalDate(2023, 10, 1), "14:00", "16:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L)
      )

      val allShifts = ShiftAssignments(Seq(
        StaffAssignment("afternoon", Terminal("terminal"), SDate(2023, 10, 1, 14, 0).millisSinceEpoch, SDate(2023, 10, 1, 15, 0).millisSinceEpoch, 0, None).splitIntoSlots(15).head,
      ))

      val updatedAssignments = StaffingUtil.updateWithDefaultShift(shifts, allShifts)

      updatedAssignments should have size 8
      updatedAssignments === List(
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 0).millisSinceEpoch, SDate(2023, 10, 1, 14, 14).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 15).millisSinceEpoch, SDate(2023, 10, 1, 14, 29).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 30).millisSinceEpoch, SDate(2023, 10, 1, 14, 44).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 45).millisSinceEpoch, SDate(2023, 10, 1, 14, 59).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 0).millisSinceEpoch, SDate(2023, 10, 1, 15, 14).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 15).millisSinceEpoch, SDate(2023, 10, 1, 15, 29).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 30).millisSinceEpoch, SDate(2023, 10, 1, 15, 44).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 45).millisSinceEpoch, SDate(2023, 10, 1, 15, 59).millisSinceEpoch, 5, None))

    }

    "not update assignments with non-zero staff" in {
      val shifts = Seq(
        StaffShift("LHR", "T1", "afternoon", LocalDate(2023, 10, 1), "14:00", "16:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L)
      )

      val allShifts = ShiftAssignments(
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 0).millisSinceEpoch, SDate(2023, 10, 1, 15, 0).millisSinceEpoch, 3, None).splitIntoSlots(15),
      )

      val updatedAssignments = StaffingUtil.updateWithDefaultShift(shifts, allShifts)

      updatedAssignments should have size 8

      updatedAssignments.toSet === Set(
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 0).millisSinceEpoch, SDate(2023, 10, 1, 14, 14).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 15).millisSinceEpoch, SDate(2023, 10, 1, 14, 29).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 30).millisSinceEpoch, SDate(2023, 10, 1, 14, 44).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 45).millisSinceEpoch, SDate(2023, 10, 1, 14, 59).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 0).millisSinceEpoch, SDate(2023, 10, 1, 15, 14).millisSinceEpoch, 5, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 15).millisSinceEpoch, SDate(2023, 10, 1, 15, 29).millisSinceEpoch, 5, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 30).millisSinceEpoch, SDate(2023, 10, 1, 15, 44).millisSinceEpoch, 5, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 45).millisSinceEpoch, SDate(2023, 10, 1, 15, 59).millisSinceEpoch, 5, None))

    }
  }

}