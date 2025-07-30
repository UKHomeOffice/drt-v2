error id: file://<WORKSPACE>/server/src/test/scala/actors/persistent/staffing/StaffingUtilSpec.scala:
file://<WORKSPACE>/server/src/test/scala/actors/persistent/staffing/StaffingUtilSpec.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -startTime.
	 -startTime#
	 -startTime().
	 -scala/Predef.startTime.
	 -scala/Predef.startTime#
	 -scala/Predef.startTime().
offset: 3662
uri: file://<WORKSPACE>/server/src/test/scala/actors/persistent/staffing/StaffingUtilSpec.scala
text:
```scala
package actors.persistent.staffing

import drt.shared.{Shift, ShiftAssignments, StaffAssignment}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow

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

  "updateWithAShiftDefaultStaff should handle overlapping times correctly" in {
    val baseDate = LocalDate(2023, 10, 1)
    val baseStartTime = SDate(2023, 10, 1, 8, 0, europeLondonTimeZone).millisSinceEpoch
    val baseEndTime = SDate(2023, 10, 1, 16, 0, europeLondonTimeZone).millisSinceEpoch

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
      createdBy = Some("test"),
      createdAt = System.currentTimeMillis()
    )

    val newShift = Shift(
      port = "LHR",
      terminal = "T1",
      shiftName = "New Shift",
      startDate = baseDate,
      startTime = "12:00", // Overlapping with previous shift
      endTime = "20:00",
      endDate = Some(baseDate),
      staffNumber = 5,
      frequency = None,
      createdBy = Some("test"),
      createdAt = System.currentTimeMillis()
    )

    val overridingShift = Seq(
      StaffShiftRow(
        // id = 1,
        port = "LHR",
        terminal = "T1",
        shiftName = "Override Shift",
        startDate = baseDate.toSqlDate,
        star@@tTime = java.sql.Time.valueOf("10:00:00"),
        endTime = java.sql.Time.valueOf("14:00:00"),
        endDate = Some(baseDate.toSqlDate),
        staffNumber = 2,
        frequency = None,
        createdBy = Some("test"),
        createdAt = java.sql.Timestamp.from(java.time.Instant.now())
      )
    )

    val existingAssignment = StaffAssignment(
      name = "Existing",
      terminal = Terminal("T1"),
      start = baseStartTime,
      end = baseEndTime,
      numberOfStaff = 1,
      createdBy = Some("existing"),
      createdAt = System.currentTimeMillis()
    )

    val allShifts = ShiftAssignments(Seq(existingAssignment))

    val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShift, newShift, allShifts)

    result must not(beEmpty) and
    result.forall(_.isInstanceOf[StaffAssignmentLike]) must beTrue
  }


    "updateWithShiftDefaultStaff should sum overlapping shifts" in {
      val shifts = Seq(
        Shift("LHR", "T1", "day", LocalDate(2023, 10, 1), "14:00", "16:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L),
        Shift("LHR", "T1", "day", LocalDate(2023, 10, 1), "15:00", "17:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L)
      )

      val allShifts = ShiftAssignments(
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, 0, None).splitIntoSlots(15)
      )

      val updatedAssignments = StaffingUtil.updateWithShiftDefaultStaff(shifts, allShifts)

      updatedAssignments must have size(12)
      // Overlapping period (15:00-16:00) should have 10 staff (5+5)
      val overlappingSlots = updatedAssignments.filter(a => 
        SDate(a.start).getHours == 15 && a.terminal.toString == "T1"
      )
      overlappingSlots.foreach(_.numberOfStaff must beEqualTo(10))
    }
  }

  "updateWithAShiftDefaultStaff" should {
    "handle overlapping shifts correctly when existing staff matches previous shift" in {
      val previousShift = Shift(
        port = "LHR",
        terminal = "T1", 
        shiftName = "Previous Shift",
        startDate = LocalDate(2024, 10, 30),
        startTime = "08:00",
        endTime = "16:00",
        endDate = Some(LocalDate(2024, 10, 30)),
        staffNumber = 5,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val overridingShift = Seq(
        StaffShiftRow(
          // id = Some(1),
          terminal = "T1",
          shiftName = "Override",
          startDate = LocalDate(2024, 10, 30),
          startTime = "12:00",
          endTime = "20:00",
          endDate = Some(LocalDate(2024, 10, 30)),
          staffNumber = 3,
          createdBy = Some("admin"),
          createdAt = System.currentTimeMillis(),
          port = "LHR"
        )
      )

      val shift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "New Shift", 
        startDate = LocalDate(2024, 10, 30),
        startTime = "10:00",
        endTime = "18:00",
        endDate = Some(LocalDate(2024, 10, 30)),
        staffNumber = 4,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val allShifts = ShiftAssignments(
        assignments = Seq(
          StaffAssignment(
            name = "Existing Assignment",
            terminal = Terminal("T1"),
            start = SDate(2024, 10, 30, 10, 0, europeLondonTimeZone).millisSinceEpoch,
            end = SDate(2024, 10, 30, 10, 15, europeLondonTimeZone).millisSinceEpoch,
            numberOfStaff = 5, // Matches previous shift staff number
            createdBy = Some("admin")
          )
        )
      )

      val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShift, shift, allShifts)

      result must not(beEmpty)
      // Should replace existing assignment since it matches previous shift staff count
      result.exists(a => a.numberOfStaff == 4) must beTrue
    }

    "preserve existing assignments when they don't match previous shift staff" in {
      val previousShift = Shift(
        port = "LHR", 
        terminal = "T1",
        shiftName = "Previous Shift",
        startDate = LocalDate(2024, 10, 30),
        startTime = "08:00", 
        endTime = "16:00",
        endDate = Some(LocalDate(2024, 10, 30)),
        staffNumber = 5,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val overridingShift = Seq.empty[StaffShiftRow]

      val shift = Shift(
        port = "LHR",
        terminal = "T1", 
        shiftName = "New Shift",
        startDate = LocalDate(2024, 10, 30),
        startTime = "10:00",
        endTime = "18:00", 
        endDate = Some(LocalDate(2024, 10, 30)),
        staffNumber = 4,
        frequency = None,
        createdBy = Some("admin"),
        createdAt = System.currentTimeMillis()
      )

      val allShifts = ShiftAssignments(
        assignments = Seq(
          StaffAssignment(
            name = "Existing Assignment",
            terminal = Terminal("T1"),
            start = SDate(2024, 10, 30, 10, 0, europeLondonTimeZone).millisSinceEpoch,
            end = SDate(2024, 10, 30, 10, 15, europeLondonTimeZone).millisSinceEpoch, 
            numberOfStaff = 7, // Different from previous shift staff number
            createdBy = Some("admin")
          )
        )
      )

      val result = StaffingUtil.updateWithAShiftDefaultStaff(previousShift, overridingShift, shift, allShifts)

      result must not(beEmpty)
      // Should preserve existing assignment since it doesn't match previous shift staff count
      result.exists(a => a.numberOfStaff == 7) must beTrue
    }
  }
}



```


#### Short summary: 

empty definition using pc, found symbol in pc: 