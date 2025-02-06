package drt.client.components

import drt.shared.{ShiftSummaryData, StaffAssignment}
import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

object ShiftAssignmentConverter {
  def toStaffAssignment(staffTableEntry: ShiftSummaryData.StaffTableEntry, terminal: Terminal): StaffAssignment = {
    StaffAssignment(
      name = staffTableEntry.name,
      terminal = terminal,
      start = SDate(staffTableEntry.startTime.year, staffTableEntry.startTime.month, staffTableEntry.startTime.day, staffTableEntry.startTime.hour, staffTableEntry.startTime.minute).millisSinceEpoch,
      end = SDate(staffTableEntry.endTime.year, staffTableEntry.endTime.month, staffTableEntry.endTime.day, staffTableEntry.endTime.hour, staffTableEntry.endTime.minute).millisSinceEpoch,
      numberOfStaff = staffTableEntry.staffNumber,
      createdBy = None
    )
  }
}