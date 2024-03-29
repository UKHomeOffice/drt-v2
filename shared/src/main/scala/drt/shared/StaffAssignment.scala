package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike
import upickle.default.{macroRW, ReadWriter => RW}


sealed trait StaffAssignmentLike extends Expireable {
  val name: String
  val terminal: Terminal
  val start: MillisSinceEpoch
  val end: MillisSinceEpoch
  val numberOfStaff: Int
  val maybeUuid: Option[String]
  val createdBy: Option[String]

  val startMinutesSinceEpoch: MillisSinceEpoch = start / 60000
  val endMinutesSinceEpoch: MillisSinceEpoch = end / 60000

  override def isExpired(expireBeforeMillis: MillisSinceEpoch): Boolean = end < expireBeforeMillis
}

object StaffAssignmentLike {
  implicit val rw: RW[StaffAssignmentLike] = RW.merge(CompleteStaffMovement.rw, StaffAssignment.rw)
}

case class StaffAssignment(name: String,
                           terminal: Terminal,
                           start: MillisSinceEpoch,
                           end: MillisSinceEpoch,
                           numberOfStaff: Int,
                           createdBy: Option[String]) extends StaffAssignmentLike {
  override val maybeUuid: Option[String] = None
}

object StaffAssignment {
  implicit val rw: RW[StaffAssignment] = macroRW
}

object CompleteStaffMovement {
  implicit val rw: RW[CompleteStaffMovement] = macroRW
}

case class CompleteStaffMovement(reason: String,
                                 terminal: Terminal,
                                 start: MillisSinceEpoch,
                                 end: MillisSinceEpoch,
                                 numberOfStaff: Int,
                                 uuid: String,
                                 createdBy: Option[String]) extends StaffAssignmentLike {
  override val name: String = reason
  override val maybeUuid: Option[String] = Option(uuid)
  val startMovement: StaffMovement = StaffMovement(terminal, reason, start, numberOfStaff, uuid, None, createdBy)
  val endMovement: StaffMovement = StaffMovement(terminal, reason, end, numberOfStaff, uuid, None, createdBy)
}

trait StaffAssignmentsLike {
  val assignments: Seq[StaffAssignmentLike]

  def terminalStaffAt(terminalName: Terminal, date: SDateLike, msToSd: MillisSinceEpoch => SDateLike): Int

  def forTerminal(terminalName: Terminal): Seq[StaffAssignmentLike] = assignments.filter(_.terminal == terminalName)

  def notForTerminal(terminalName: Terminal): Seq[StaffAssignmentLike] =
    assignments.filterNot(_.terminal == terminalName)
}

object StaffAssignmentsLike {
  implicit val rw: RW[StaffAssignmentsLike] = RW.merge(ShiftAssignments.rw, FixedPointAssignments.rw)
}

object ShiftAssignments {
  val empty: ShiftAssignments = ShiftAssignments(Seq())

  implicit val rw: RW[ShiftAssignments] = macroRW
}

case class ShiftAssignments(assignments: Seq[StaffAssignmentLike]) extends StaffAssignmentsLike with HasExpireables[ShiftAssignments] {
  def terminalStaffAt(terminalName: Terminal, date: SDateLike, msToSd: MillisSinceEpoch => SDateLike): Int = {
    val dateMinutesSinceEpoch = date.millisSinceEpoch / 60000

    assignments
      .filter { assignment =>
        assignment.startMinutesSinceEpoch <= dateMinutesSinceEpoch &&
          dateMinutesSinceEpoch <= assignment.endMinutesSinceEpoch && assignment.terminal == terminalName
      }
      .map(_.numberOfStaff)
      .sum
  }

  def purgeExpired(expireBefore: () => SDateLike): ShiftAssignments = {
    val expireBeforeMillis = expireBefore().millisSinceEpoch
    copy(assignments = assignments.filterNot(_.isExpired(expireBeforeMillis)))
  }
}

object FixedPointAssignments {
  def empty(implicit msToSd: MillisSinceEpoch => SDateLike): FixedPointAssignments = FixedPointAssignments(Seq())

  implicit val rw: RW[FixedPointAssignments] = macroRW
}

case class FixedPointAssignments(assignments: Seq[StaffAssignmentLike]) extends StaffAssignmentsLike {
  def +(staffAssignments: Seq[StaffAssignmentLike]): FixedPointAssignments = copy(assignments ++ staffAssignments)

  def terminalStaffAt(terminalName: Terminal, date: SDateLike, msToSd: MillisSinceEpoch => SDateLike): Int = {
    val hoursAndMinutes = date.toHoursAndMinutes

    assignments
      .filter { assignment =>
        assignment.terminal == terminalName &&
          hoursAndMinutes >= msToSd(assignment.start).toHoursAndMinutes &&
          hoursAndMinutes <= msToSd(assignment.end).toHoursAndMinutes
      }
      .map(_.numberOfStaff)
      .sum
  }

  def diff(other: FixedPointAssignments): FixedPointAssignments = {
    val diffSet = other.assignments.toSet.diff(assignments.toSet) ++
      assignments.toSet.diff(other.assignments.toSet)
    FixedPointAssignments(diffSet.toList)
  }
}
