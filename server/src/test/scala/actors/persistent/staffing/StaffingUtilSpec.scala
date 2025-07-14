package actors.persistent.staffing

import drt.shared.{Shift, ShiftAssignments, StaffAssignment}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil._
import java.sql.Timestamp
class StaffingUtilSpec extends Specification {

  "StaffingUtil" should {
    "generate daily assignments for each day between start and end date" in {
      val shift = Shift(
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

      val expectedStartMillis = SDate(2023, 10, 1, 8, 0, europeLondonTimeZone).millisSinceEpoch
      val expectedEndMillis = SDate(2023, 10, 3, 16, 0, europeLondonTimeZone).millisSinceEpoch

      SDate(assignments.head.start).toISOString must beEqualTo(SDate(expectedStartMillis).toISOString)
      SDate(assignments.last.end).toISOString must beEqualTo(SDate(expectedEndMillis).toISOString)
    }

    "updateWithShiftDefaultStaff should update assignments with zero staff" in {
      val shifts = Seq(
        Shift("LHR", "T1", "day", LocalDate(2023, 10, 1), "14:00", "16:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L)
      )

      val allShifts = ShiftAssignments(
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, 0, None).splitIntoSlots(15)
      )

      val updatedAssignments = StaffingUtil.updateWithShiftDefaultStaff(shifts, allShifts)

      updatedAssignments must have size(8)
      updatedAssignments.head.numberOfStaff must beEqualTo(5)
    }

   "updateWithAShiftDefaultStaff should update when existing staff equals overriding + previous shift staff" in {
  val baseDate = LocalDate(2024, 10, 30)
  
  val previousShift = Shift(
    port = "LHR",
    terminal = "T1",
    shiftName = "Previous Shift",
    startDate = baseDate,
    startTime = "08:00",
    endTime = "16:00",
    endDate = Some(baseDate),
    staffNumber = 3, // Previous shift had 3 staff
    frequency = None,
    createdBy = Some("admin"),
    createdAt = System.currentTimeMillis()
  )

  val overridingShift = Seq(
    StaffShiftRow(
      // id = Some(1),
      port = "LHR",
      terminal = "T1",
      shiftName = "Override Shift",
      startDate = ShiftUtil.convertToSqlDate(baseDate),
      startTime = "10:00",
      endTime = "14:00",
      endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
      staffNumber = 2, // Override adds 2 staff
      frequency = None,
      createdBy = Some("admin"),
      createdAt = new Timestamp(System.currentTimeMillis())
    )
  )

  val newShift = Shift(
    port = "LHR",
    terminal = "T1",
    shiftName = "New Shift",
    startDate = baseDate,
    startTime = "10:00",
    endTime = "14:00",
    endDate = Some(baseDate),
    staffNumber = 4, // New shift wants 4 staff
    frequency = None,
    createdBy = Some("admin"),
    createdAt = System.currentTimeMillis()
  )

  // Existing assignment has 5 staff (2 override + 3 previous = 5)
  val existingAssignment = StaffAssignment(
    name = "Existing Assignment",
    terminal = Terminal("T1"),
    start = SDate(2024, 10, 30, 10, 0, europeLondonTimeZone).millisSinceEpoch,
    end = SDate(2024, 10, 30, 10, 15, europeLondonTimeZone).millisSinceEpoch,
    numberOfStaff = 5, // This equals overridingStaff(2) + previousShift.staffNumber(3)
    createdBy = Some("admin")
  )

  val allShifts = ShiftAssignments(assignments = Seq(existingAssignment))

  val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShift, newShift, allShifts)

  result must not(beEmpty) and
  // Should update to overridingStaff(2) + newShift.staffNumber(4) = 6
  result.exists(a => a.numberOfStaff == 6) //must beTrue
}

"updateWithAShiftDefaultStaff should preserve existing when staff doesn't match override + previous" in {
  val baseDate = LocalDate(2024, 10, 30)
  
  val previousShift = Shift(
    port = "LHR",
    terminal = "T1",
    shiftName = "Previous Shift",
    startDate = baseDate,
    startTime = "08:00",
    endTime = "16:00",
    endDate = Some(baseDate),
    staffNumber = 3,
    frequency = None,
    createdBy = Some("admin"),
    createdAt = System.currentTimeMillis()
  )

  val overridingShift = Seq(
    StaffShiftRow(
      port = "LHR",
      terminal = "T1",
      shiftName = "Override Shift",
      startDate = ShiftUtil.convertToSqlDate(baseDate),
      startTime = "10:00",
      endTime = "14:00",
      endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
      staffNumber = 2,
      createdBy = Some("admin"),
      frequency = None,
      createdAt = new Timestamp(System.currentTimeMillis())
    )
  )

  val newShift = Shift(
    port = "LHR",
    terminal = "T1",
    shiftName = "New Shift",
    startDate = baseDate,
    startTime = "10:00",
    endTime = "14:00",
    endDate = Some(baseDate),
    staffNumber = 4,
    frequency = None,
    createdBy = Some("admin"),
    createdAt = System.currentTimeMillis()
  )

  // Existing assignment has 8 staff (doesn't equal 2 + 3 = 5)
  val existingAssignment = StaffAssignment(
    name = "Existing Assignment",
    terminal = Terminal("T1"),
    start = SDate(2024, 10, 30, 10, 0, europeLondonTimeZone).millisSinceEpoch,
    end = SDate(2024, 10, 30, 10, 15, europeLondonTimeZone).millisSinceEpoch,
    numberOfStaff = 8, // This does NOT equal overridingStaff(2) + previousShift.staffNumber(3)
    createdBy = Some("admin")
  )

  val allShifts = ShiftAssignments(assignments = Seq(existingAssignment))

  val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShift, newShift, allShifts)

  result must not(beEmpty) and
  // Should preserve existing assignment since 8 != (2 + 3)
  result.exists(a => a.numberOfStaff == 8) //must beTrue
}

"updateWithAShiftDefaultStaff should handle case with no overriding shift" in {
  val baseDate = LocalDate(2024, 10, 30)
  
  val previousShift = Shift(
    port = "LHR",
    terminal = "T1",
    shiftName = "Previous Shift",
    startDate = baseDate,
    startTime = "08:00",
    endTime = "16:00",
    endDate = Some(baseDate),
    staffNumber = 3,
    frequency = None,
    createdBy = Some("admin"),
    createdAt = System.currentTimeMillis()
  )

  val overridingShift = Seq.empty[StaffShiftRow] // No overriding shifts

  val newShift = Shift(
    port = "LHR",
    terminal = "T1",
    shiftName = "New Shift",
    startDate = baseDate,
    startTime = "10:00",
    endTime = "14:00",
    endDate = Some(baseDate),
    staffNumber = 4,
    frequency = None,
    createdBy = Some("admin"),
    createdAt = System.currentTimeMillis()
  )

  // Existing assignment has 3 staff (equals previousShift.staffNumber but no overriding)
  val existingAssignment = StaffAssignment(
    name = "Existing Assignment",
    terminal = Terminal("T1"),
    start = SDate(2024, 10, 30, 10, 0, europeLondonTimeZone).millisSinceEpoch,
    end = SDate(2024, 10, 30, 10, 15, europeLondonTimeZone).millisSinceEpoch,
    numberOfStaff = 3, // This equals 0 (no override) + previousShift.staffNumber(3)
    createdBy = Some("admin")
  )

  val allShifts = ShiftAssignments(assignments = Seq(existingAssignment))

  val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShift, newShift, allShifts)

  result must not(beEmpty) and
  // Should update to 0 (no override) + newShift.staffNumber(4) = 4
  result.exists(a => a.numberOfStaff == 4) //must beTrue
}
}
}