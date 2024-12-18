package actors.persistent.staffing

import drt.shared.{StaffAssignment, StaffShift}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.LocalDate

import java.time.{LocalTime, ZoneId, LocalDate => JavaLocalDate}


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
        assignment.terminal must beEqualTo("T1")
        assignment.numberOfStaff must beEqualTo(5)
        assignment.createdBy must beEqualTo(Some("test"))
      }

      val expectedStartMillis = JavaLocalDate.of(2023, 10, 1)
        .atTime(LocalTime.of(8, 0))
        .atZone(ZoneId.systemDefault())
        .toInstant
        .toEpochMilli

      val expectedEndMillis = JavaLocalDate.of(shift.endDate.get.year, shift.endDate.get.month, shift.endDate.get.day)
        .atTime(LocalTime.of(16, 0))
        .atZone(ZoneId.systemDefault())
        .toInstant
        .toEpochMilli

      assignments.head.start must beEqualTo(expectedStartMillis)
      assignments.last.end must beEqualTo(expectedEndMillis)
    }
  }
}