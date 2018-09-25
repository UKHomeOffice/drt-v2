package drt.shared

import drt.shared.FlightsApi.TerminalName


case class StaffAssignment(name: String,
                           terminalName: TerminalName,
                           startDt: MilliDate,
                           endDt: MilliDate,
                           numberOfStaff: Int,
                           createdBy: Option[String])

sealed trait StaffAssignments {
  val assignments: Seq[StaffAssignment]

  def forTerminal(terminalName: String): Seq[StaffAssignment] = assignments.filter(_.terminalName == terminalName)

  def notForTerminal(terminalName: TerminalName): Seq[StaffAssignment] =
    assignments.filterNot(_.terminalName == terminalName)
}

trait FixedPointAssignmentsLike extends StaffAssignments {
  def terminalStaffAt(terminalName: TerminalName, date: SDateLike)(implicit mdToSd: MilliDate => SDateLike): Int
}

trait ShiftAssignmentsLike extends StaffAssignments {
  def terminalStaffAt(terminalName: TerminalName, date: SDateLike): Int
}

case class FixedPointAssignments(assignments: Seq[StaffAssignment]) extends FixedPointAssignmentsLike {
  def +(staffAssignments: Seq[StaffAssignment]): FixedPointAssignments = copy(assignments ++ staffAssignments)

  def terminalStaffAt(terminalName: TerminalName, date: SDateLike)
                     (implicit mdToSd: MilliDate => SDateLike): Int = assignments.filter(assignment => {
    assignment.terminalName == terminalName &&
      date.toHoursAndMinutes() >= mdToSd(assignment.startDt).toHoursAndMinutes() &&
      date.toHoursAndMinutes() <= mdToSd(assignment.endDt).toHoursAndMinutes()
  }).map(_.numberOfStaff).sum
}

case class ShiftAssignments(assignments: Seq[StaffAssignment]) extends ShiftAssignmentsLike {
  def +(staffAssignments: Seq[StaffAssignment]): ShiftAssignments = copy(assignments ++ staffAssignments)

  def terminalStaffAt(terminalName: TerminalName, date: SDateLike): Int = {
    val dateInQuestion = date.millisSinceEpoch
    assignments.filter(assignment => {
      assignment.startDt.millisSinceEpoch <= dateInQuestion && dateInQuestion <= assignment.endDt.millisSinceEpoch && assignment.terminalName == terminalName
    }).map(_.numberOfStaff).sum
  }
}

object FixedPointAssignments {
  val empty: FixedPointAssignments = FixedPointAssignments(Seq())
}

object ShiftAssignments {
  val empty: ShiftAssignments = ShiftAssignments(Seq())
}
