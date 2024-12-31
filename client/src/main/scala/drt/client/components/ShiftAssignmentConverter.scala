package drt.client.components

import drt.shared.StaffAssignment
import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

object ShiftAssignmentConverter {
  def toStaffAssignment(shiftAssignment: ShiftAssignment, terminal: Terminal): StaffAssignment = {
    StaffAssignment(
      name = shiftAssignment.name,
      terminal = terminal,
      start = SDate(shiftAssignment.startTime.year, shiftAssignment.startTime.month, shiftAssignment.startTime.day, shiftAssignment.startTime.hour, shiftAssignment.startTime.minute).millisSinceEpoch,
      end = SDate(shiftAssignment.endTime.year, shiftAssignment.endTime.month, shiftAssignment.endTime.day, shiftAssignment.endTime.hour, shiftAssignment.endTime.minute).millisSinceEpoch,
      numberOfStaff = shiftAssignment.staffNumber,
      createdBy = None
    )
  }
}