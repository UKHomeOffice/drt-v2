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

      updatedAssignments must have size (8)
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

    "updateWithAShiftDefaultStaff should handle multiple overlapping shifts on same terminal" in {
      val baseDate = LocalDate(2024, 10, 30)

      val previousShift = Shift(
        port = "LHR",
        terminal = "T1", // Same terminal for all operations
        shiftName = "Previous Shift",
        startDate = baseDate,
        startTime = "08:00",
        endTime = "16:00",
        endDate = Some(baseDate),
        staffNumber = 2,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val overridingShifts = Seq(
        StaffShiftRow(
          port = "LHR",
          terminal = "T1", // Same terminal
          shiftName = "Override Shift 1",
          startDate = ShiftUtil.convertToSqlDate(baseDate),
          startTime = "10:00",
          endTime = "14:00",
          endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
          staffNumber = 3,
          frequency = None,
          createdBy = Some("admin"),
          createdAt = new Timestamp(System.currentTimeMillis())
        ),
        StaffShiftRow(
          port = "LHR",
          terminal = "T1", // Same terminal
          shiftName = "Override Shift 2",
          startDate = ShiftUtil.convertToSqlDate(baseDate),
          startTime = "12:00",
          endTime = "18:00",
          endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
          staffNumber = 1,
          frequency = None,
          createdBy = Some("admin"),
          createdAt = new Timestamp(System.currentTimeMillis())
        ),
        StaffShiftRow(
          port = "LHR",
          terminal = "T1", // Same terminal
          shiftName = "Override Shift 3",
          startDate = ShiftUtil.convertToSqlDate(baseDate),
          startTime = "11:00",
          endTime = "15:00",
          endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
          staffNumber = 2,
          frequency = None,
          createdBy = Some("admin"),
          createdAt = new Timestamp(System.currentTimeMillis())
        )
      )

      val newShift = Shift(
        port = "LHR",
        terminal = "T1", // Same terminal
        shiftName = "New Shift",
        startDate = baseDate,
        startTime = "09:00",
        endTime = "17:00",
        endDate = Some(baseDate),
        staffNumber = 6,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      // Existing assignments at different times with overlapping staff patterns
      val existingAssignments = Seq(
        StaffAssignment(
          name = "Existing 10:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 10, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 10, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 5, // 3 (override1) + 2 (previous) = 5
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Existing 12:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 12, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 12, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 8, // 6 (override2) + 2 (previous) = 8
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Existing 11:30",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 11, 30, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 11, 45, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 7, // 5 (override3) + 2 (previous) = 7
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Existing 19:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 19, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 19, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 7, // Doesn't match any override + previous pattern
          createdBy = Some("admin")
        )
      )

      val allShifts = ShiftAssignments(assignments = existingAssignments)

      val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShifts, newShift, allShifts)

      result must not(beEmpty) and
        result.forall(_.terminal.toString == "T1") and
        result.exists(a => SDate(a.start).getHours == 12 && a.numberOfStaff == 12) and // 6 + 6 = 12 for override1
        result.exists(a => SDate(a.start).getHours == 10 && a.numberOfStaff == 9) and // 3 + 6 = 9 for override2
        result.exists(a => SDate(a.start).getHours == 11 && SDate(a.start).getMinutes ==  30 && a.numberOfStaff == 11) //and // 5 + 6 = 11 for override3
//        result.exists(a => SDate(a.start).getHours == 19 && a.numberOfStaff == 7)     // Should preserve the 7 staff assignment this is outside new shift period
    }

    "updateWithAShiftDefaultStaff should handle long shift spans with same terminal overlaps" in {
      val baseDate = LocalDate(2024, 10, 30)

      val previousShift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Previous Long Shift",
        startDate = baseDate,
        startTime = "06:00",
        endTime = "22:00",
        endDate = Some(baseDate),
        staffNumber = 4,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val overridingShifts = Seq(
        StaffShiftRow(
          port = "LHR",
          terminal = "T1",
          shiftName = "Morning Override",
          startDate = ShiftUtil.convertToSqlDate(baseDate),
          startTime = "08:00",
          endTime = "12:00",
          endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
          staffNumber = 2,
          frequency = None,
          createdBy = Some("admin"),
          createdAt = new Timestamp(System.currentTimeMillis())
        ),
        StaffShiftRow(
          port = "LHR",
          terminal = "T1",
          shiftName = "Afternoon Override",
          startDate = ShiftUtil.convertToSqlDate(baseDate),
          startTime = "14:00",
          endTime = "18:00",
          endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
          staffNumber = 3,
          frequency = None,
          createdBy = Some("admin"),
          createdAt = new Timestamp(System.currentTimeMillis())
        ),
        StaffShiftRow(
          port = "LHR",
          terminal = "T1",
          shiftName = "Evening Override",
          startDate = ShiftUtil.convertToSqlDate(baseDate),
          startTime = "19:00",
          endTime = "23:00",
          endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
          staffNumber = 1,
          frequency = None,
          createdBy = Some("admin"),
          createdAt = new Timestamp(System.currentTimeMillis())
        )
      )

      val newShift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "New Full Day Shift",
        startDate = baseDate,
        startTime = "07:00",
        endTime = "20:00",
        endDate = Some(baseDate),
        staffNumber = 8,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val existingAssignments = Seq(
        StaffAssignment(
          name = "Morning 09:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 9, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 9, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 6,
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Lunch 13:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 13, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 13, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 4,
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Afternoon 15:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 15, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 15, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 7,
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Evening 20:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 20, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 20, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 5,
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Non-matching 16:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 16, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 16, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 10,
          createdBy = Some("admin")
        )
      )

      val allShifts = ShiftAssignments(assignments = existingAssignments)

      val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShifts, newShift, allShifts)

      (result must not(beEmpty) and
        result.forall(_.terminal.toString == "T1") and
//        (result.length must beGreaterThan(40)) and
        result.exists(a => SDate(a.start).getHours == 9 && a.numberOfStaff == 10) and
        result.exists(a => SDate(a.start).getHours == 13 && a.numberOfStaff == 8) and
        result.exists(a => SDate(a.start).getHours == 15 && a.numberOfStaff == 11) and
//        result.exists(a => SDate(a.start).getHours == 20 && a.numberOfStaff == 1) //this is not part of the new shift
        result.exists(a => SDate(a.start).getHours == 16 && a.numberOfStaff == 10)
      )
    }

    "updateWithAShiftDefaultStaff should handle zero staff existing assignments on same terminal" in {
      val baseDate = LocalDate(2024, 10, 30)

      val previousShift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Previous Shift",
        startDate = baseDate,
        startTime = "08:00",
        endTime = "16:00",
        endDate = Some(baseDate),
        staffNumber = 5,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val overridingShift = Seq(
        StaffShiftRow(
          port = "LHR",
          terminal = "T1", // Same terminal
          shiftName = "Override Shift",
          startDate = ShiftUtil.convertToSqlDate(baseDate),
          startTime = "10:00",
          endTime = "14:00",
          endDate = Some(ShiftUtil.convertToSqlDate(baseDate)),
          staffNumber = 3,
          frequency = None,
          createdBy = Some("admin"),
          createdAt = new Timestamp(System.currentTimeMillis())
        )
      )

      val newShift = Shift(
        port = "LHR",
        terminal = "T1", // Same terminal
        shiftName = "New Shift",
        startDate = baseDate,
        startTime = "09:00",
        endTime = "15:00",
        endDate = Some(baseDate),
        staffNumber = 7,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      // Mix of zero and non-zero staff assignments on same terminal
      val existingAssignments = Seq(
        StaffAssignment(
          name = "Zero Staff 10:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 10, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 10, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 0, // Zero staff - should be replaced
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Previous Staff 11:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 11, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 11, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 5, // Equals previous shift staff - should be replaced
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Override + Previous 12:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 12, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 12, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 8, // 3 + 5 = 8, should be updated to 3 + 7 = 10
          createdBy = Some("admin")
        ),
        StaffAssignment(
          name = "Other Staff 13:00",
          terminal = Terminal("T1"),
          start = SDate(2024, 10, 30, 13, 0, europeLondonTimeZone).millisSinceEpoch,
          end = SDate(2024, 10, 30, 13, 15, europeLondonTimeZone).millisSinceEpoch,
          numberOfStaff = 12, // Doesn't match any pattern - should be preserved
          createdBy = Some("admin")
        )
      )

      val allShifts = ShiftAssignments(assignments = existingAssignments)

      val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShift, newShift, allShifts)

      result must not(beEmpty) and
        result.forall(_.terminal.toString == "T1") and
        (result.count(a => a.numberOfStaff == 7) > 10 must beTrue) and
        result.exists(a => a.numberOfStaff == 10) and
        result.exists(a => a.numberOfStaff == 12)
    }
  }
}