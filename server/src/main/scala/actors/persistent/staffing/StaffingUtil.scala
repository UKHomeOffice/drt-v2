package actors.persistent.staffing

import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike, StaffShift}
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
      val dailyAssignments = generateDailyAssignments(shift)
      val splitDailyAssignments = dailyAssignments.flatMap(_.splitIntoSlots(15))
      val existingAllAssignments = allShifts.assignments.sortBy(_.start)//.flatMap(_.splitIntoSlots(15))
      existingAllAssignments.map { existing =>
        splitDailyAssignments.find { assignment =>
          val existingStart = SDate(existing.start)
          val existingEnd = SDate(existing.end)
          val assignmentStart = SDate(assignment.start)
          val assignmentEnd = SDate(assignment.end)

          existingStart == assignmentStart && existingEnd == assignmentEnd
        }.map { assignment =>
          if (existing.numberOfStaff == 0) assignment else existing
        }.getOrElse(existing)
      } ++
      splitDailyAssignments.map { assignment =>
        existingAllAssignments.find { existing =>
          val existingStart = SDate(existing.start)
          val existingEnd = SDate(existing.end)
          val assignmentStart = SDate(assignment.start)
          val assignmentEnd = SDate(assignment.end)
          existingStart == assignmentStart && existingEnd == assignmentEnd
        }.map { existing =>
          if (existing.numberOfStaff == 0) assignment else existing
        }.getOrElse(assignment)
      }
    }
    updatedAssignments
  }

}
