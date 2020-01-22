package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import upickle.default.{macroRW, ReadWriter => RW}

case class StaffAssignment(name: String,
                           terminal: Terminal,
                           startDt: MilliDate,
                           endDt: MilliDate,
                           numberOfStaff: Int,
                           createdBy: Option[String]) extends Expireable {
  def isExpired(expireBeforeMillis: MillisSinceEpoch): Boolean = endDt.millisSinceEpoch < expireBeforeMillis
}

object StaffAssignment {
  implicit val rw: RW[StaffAssignment] = macroRW
}

sealed trait StaffAssignments {
  val assignments: Seq[StaffAssignment]

  def forTerminal(terminalName: Terminal): Seq[StaffAssignment] = assignments.filter(_.terminal == terminalName)

  def notForTerminal(terminalName: Terminal): Seq[StaffAssignment] =
    assignments.filterNot(_.terminal == terminalName)
}

trait FixedPointAssignmentsLike extends StaffAssignments {
  def terminalStaffAt(terminalName: Terminal, date: SDateLike)(implicit mdToSd: MilliDate => SDateLike): Int
}

trait ShiftAssignmentsLike extends StaffAssignments {
  def terminalStaffAt(terminalName: Terminal, date: SDateLike): Int
}

object ShiftAssignments {
  val empty: ShiftAssignments = ShiftAssignments(Seq())

  def apply(assignments: Set[StaffAssignment]): ShiftAssignments = ShiftAssignments(assignments.toSeq)
}

case class FixedPointAssignments(assignments: Seq[StaffAssignment]) extends FixedPointAssignmentsLike {
  def +(staffAssignments: Seq[StaffAssignment]): FixedPointAssignments = copy(assignments ++ staffAssignments)

  def terminalStaffAt(terminalName: Terminal, date: SDateLike)
                     (implicit mdToSd: MilliDate => SDateLike): Int = {
    val hoursAndMinutes = date.toHoursAndMinutes()

    assignments
      .filter { assignment =>
        assignment.terminal == terminalName &&
          hoursAndMinutes >= mdToSd(assignment.startDt).toHoursAndMinutes() &&
          hoursAndMinutes <= mdToSd(assignment.endDt).toHoursAndMinutes()
      }
      .map(_.numberOfStaff)
      .sum
  }
}

object FixedPointAssignments {
  val empty: FixedPointAssignments = FixedPointAssignments(Seq())
  implicit val rw: RW[FixedPointAssignments] = macroRW
}

case class ShiftAssignments(assignments: Seq[StaffAssignment]) extends ShiftAssignmentsLike with HasExpireables[ShiftAssignments] {
  def +(staffAssignments: Seq[StaffAssignment]): ShiftAssignments = copy(assignments ++ staffAssignments)

  def terminalStaffAt(terminalName: Terminal, date: SDateLike): Int = {
    val dateMillis = date.millisSinceEpoch

    assignments
      .filter { assignment =>
        assignment.startDt.millisSinceEpoch <= dateMillis && dateMillis <= assignment.endDt.millisSinceEpoch && assignment.terminal == terminalName
      }
      .map(_.numberOfStaff)
      .sum
  }

  def purgeExpired(expireBefore: () => SDateLike): ShiftAssignments = {
    val expireBeforeMillis = expireBefore().millisSinceEpoch
    copy(assignments = assignments.filterNot(_.isExpired(expireBeforeMillis)))
  }
}

