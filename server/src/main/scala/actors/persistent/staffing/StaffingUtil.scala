package actors.persistent.staffing

import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike, StaffShift, TM}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

object StaffingUtil {
  def generateDailyAssignments(shift: StaffShift): Seq[StaffAssignment] = {
    val (startHH, startMM) = shift.startTime.split(":") match {
      case Array(hh, mm) => (hh.toInt, mm.toInt)
    }
    val (endHH, endMM) = shift.endTime.split(":") match {
      case Array(hh, mm) => (hh.toInt, mm.toInt)
    }
    val startDate = SDate(shift.startDate.year, shift.startDate.month, shift.startDate.day)
    val endDate = shift.endDate.map(ed => SDate(ed.year, ed.month, ed.day)).getOrElse(startDate.addMonths(6))
    val daysBetween = startDate.daysBetweenInclusive(endDate) - 1
    (0 to daysBetween).map { day =>
      val currentDate: SDateLike = startDate.addDays(day)
      val startMillis = SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, startHH, startMM).millisSinceEpoch
      val endMillis = SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM).millisSinceEpoch

      StaffAssignment(
        name = shift.shiftName,
        terminal = Terminal(shift.terminal),
        start = startMillis,
        end = endMillis,
        numberOfStaff = shift.staffNumber,
        createdBy = shift.createdBy
      )
    }
  }

  def updateWithDefaultShift(shifts: Seq[StaffShift], allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {
    val updatedAssignments: Seq[StaffAssignmentLike] = shifts.flatMap { shift =>
      val dailyAssignments: Seq[StaffAssignment] = generateDailyAssignments(shift)
      val splitDailyAssignments = dailyAssignments.flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
        .map(a => TM(a.terminal, a.start) -> a)
        .toMap
      val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap

      splitDailyAssignments.map {
        case (tm, assignment) =>
          existingAllAssignments.get(tm) match {
            case Some(existing) =>
              if (existing.numberOfStaff == 0) assignment else existing
            case None => assignment
          }
      }.toSeq.sortBy(_.start)
    }
    updatedAssignments
  }

}
