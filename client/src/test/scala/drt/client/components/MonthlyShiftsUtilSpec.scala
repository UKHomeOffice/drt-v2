package drt.client.components

import utest._

class MonthlyShiftsUtilSpec extends TestSuite {
  val tests: Tests = Tests {
    test("updateAssignments should update assignments correctly") {
      val shifts = Seq(
        ShiftSummaryStaffing(1, ShiftSummary("Morning", 5, "08:00", "10:00"), Seq(
          StaffTableEntry(1, 0, "Morning", 5, ShiftDate(2023, 10, 1, 8, 0), ShiftDate(2023, 10, 1, 9, 0)),
          StaffTableEntry(1, 1, "Morning", 5, ShiftDate(2023, 10, 1, 9, 0), ShiftDate(2023, 10, 1, 10, 0))
        ))
      )
      val changedAssignments = Seq(
        StaffTableEntry(1, 0, "Morning", 6, ShiftDate(2023, 10, 1, 8, 0), ShiftDate(2023, 10, 1, 9, 0))
      )
      val slotMinutes = 60

      val result = MonthlyShiftsUtil.updateAssignments(shifts, changedAssignments, slotMinutes)

      assert(result.size == 1)
      assert(result.head.staffTableEntries.size == 2)
      assert(result.head.staffTableEntries.exists(entry => entry.staffNumber == 6 && entry.startTime == ShiftDate(2023, 10, 1, 8, 0)))
      assert(result.head.staffTableEntries.exists(entry => entry.staffNumber == 5 && entry.startTime == ShiftDate(2023, 10, 1, 9, 0)))
    }


    test("updateChangeAssignment should merge previous and new changes correctly") {
      val previousChange = Seq(
        StaffTableEntry(1, 0, "Morning", 5, ShiftDate(2023, 10, 1, 8, 0), ShiftDate(2023, 10, 1, 9, 0)),
        StaffTableEntry(1, 1, "Morning", 5, ShiftDate(2023, 10, 1, 9, 0), ShiftDate(2023, 10, 1, 10, 0))
      )
      val newChange = Seq(
        StaffTableEntry(1, 0, "Morning", 6, ShiftDate(2023, 10, 1, 8, 0), ShiftDate(2023, 10, 1, 9, 0)),
        StaffTableEntry(1, 2, "Morning", 5, ShiftDate(2023, 10, 1, 10, 0), ShiftDate(2023, 10, 1, 11, 0))
      )

      val result = MonthlyShiftsUtil.updateChangeAssignment(previousChange, newChange)

      assert(result.size == 3)
      assert(result.exists(entry => entry.staffNumber == 6 && entry.startTime == ShiftDate(2023, 10, 1, 8, 0)))
      assert(result.exists(entry => entry.staffNumber == 5 && entry.startTime == ShiftDate(2023, 10, 1, 9, 0)))
      assert(result.exists(entry => entry.staffNumber == 5 && entry.startTime == ShiftDate(2023, 10, 1, 10, 0)))
    }

  }
}
