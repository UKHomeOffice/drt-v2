package drt.shared

import drt.shared.FlightsApi.TerminalName


case class StaffAssignment(name: String, terminalName: TerminalName, startDt: MilliDate, endDt: MilliDate, numberOfStaff: Int, createdBy: Option[String])

case class StaffAssignments(assignments: Seq[StaffAssignment]) {
  def forTerminal(terminalName: String): StaffAssignments = StaffAssignments(assignments.filter(_.terminalName == terminalName))
  def +(staffAssignments: StaffAssignments) = StaffAssignments(assignments ++ staffAssignments.assignments)
}

object StaffAssignments {
  val empty: StaffAssignments = StaffAssignments(Seq())
}
