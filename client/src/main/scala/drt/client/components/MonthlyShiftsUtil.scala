package drt.client.components


object MonthlyShiftsUtil {

  def updateChangeAssignment(previousChange: Seq[StaffTableEntry], newChange: Seq[StaffTableEntry]): Seq[StaffTableEntry] = {
    val previousChangeMap = previousChange.map(a => (ShiftDate.toString(a.startTime)) -> a).toMap
    val newChangeMap = newChange.map(a => (ShiftDate.toString(a.startTime)) -> a).toMap
    val mergedMap = previousChangeMap ++ newChangeMap
    mergedMap.values.toSeq
  }

  def updateAssignments(shifts: Seq[ShiftSummaryStaffing], changedAssignments: Seq[StaffTableEntry], slotMinutes: Int): Seq[ShiftSummaryStaffing] = {
    val changedAssignmentsWithSlotMap: Seq[StaffTableEntry] = changedAssignments.flatMap(a => StaffTableEntry.splitIntoSlots(a, slotMinutes))
    val changedAssignmentsMap: Map[String, StaffTableEntry] = changedAssignmentsWithSlotMap.map(a => (ShiftDate.toString(a.startTime)) -> a).toMap
    shifts.map { shift: ShiftSummaryStaffing =>
      val updatedAssignments = shift.staffTableEntries.map { assignment =>
        changedAssignmentsMap.getOrElse(ShiftDate.toString(assignment.startTime), assignment)
      }
      ShiftSummaryStaffing(shift.index, shift.shiftSummary, updatedAssignments.toSeq)
    }
  }
}
