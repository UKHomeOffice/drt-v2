package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.SDateLike
import upickle.default.{macroRW, ReadWriter => RW}

import scala.concurrent.duration.DurationInt

case class StaffAssignmentKey(terminal: Terminal, start: MillisSinceEpoch, end: MillisSinceEpoch)

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

  def key: StaffAssignmentKey = {
    val intervalMillis = 14.minutes.toMillis
    val breakMillis = 1.minute.toMillis
    val totalIntervalMillis = intervalMillis + breakMillis
    val startOfHour = start - (start % 1.hour.toMillis)
    val intervalIndex = ((start % 1.hour.toMillis) / totalIntervalMillis).toInt
    val intervalStart = startOfHour + (intervalIndex * totalIntervalMillis)

    StaffAssignmentKey(
      terminal = terminal,
      start = intervalStart,
      end = intervalStart + intervalMillis
    )
  }

  override def isExpired(expireBeforeMillis: MillisSinceEpoch): Boolean = end < expireBeforeMillis

  def splitIntoSlots(slotMinutes: Int): Seq[StaffAssignment]
}

object StaffAssignmentLike {
  implicit val rw: RW[StaffAssignmentLike] = RW.merge(/*CompleteStaffMovement.rw, */StaffAssignment.rw)
}

case class StaffAssignment(name: String,
                           terminal: Terminal,
                           start: MillisSinceEpoch,
                           end: MillisSinceEpoch,
                           numberOfStaff: Int,
                           createdBy: Option[String]) extends StaffAssignmentLike {
  override val maybeUuid: Option[String] = None

  override def splitIntoSlots(slotMinutes: Int): Seq[StaffAssignment] =
    (start until end by slotMinutes.minutes.toMillis).map(start =>
      StaffAssignment(
        name = name,
        terminal = terminal,
        start = start,
        end = start + (slotMinutes.minutes.toMillis - oneMinuteMillis),
        numberOfStaff = numberOfStaff,
        createdBy = createdBy
      )
    )
}

object StaffAssignment {
  implicit val rw: RW[StaffAssignment] = macroRW
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
  implicit val rw: RW[ShiftAssignments] = macroRW

  val periodLengthMinutes: Int = 15

  val empty: ShiftAssignments = ShiftAssignments(Map[TM, StaffAssignmentLike]())

  def apply(assignments: Seq[StaffAssignmentLike]): ShiftAssignments =
    ShiftAssignments(assignments.map(a => TM(a.terminal, a.start) -> a).toMap)
}

case class ShiftAssignments(indexedAssignments: Map[TM, StaffAssignmentLike]) extends StaffAssignmentsLike with HasExpireables[ShiftAssignments] {
  lazy val assignments: Seq[StaffAssignmentLike] = indexedAssignments.values.toSeq
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
    copy(indexedAssignments = indexedAssignments.filterNot(_._2.isExpired(expireBeforeMillis)))
  }

  def applyUpdates(updates: Seq[StaffAssignmentLike]): ShiftAssignments = {
    val updatedAssignments = updates
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .map(a => TM(a.terminal, a.start) -> a)
      .toMap
    copy(indexedAssignments = indexedAssignments ++ updatedAssignments)
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
