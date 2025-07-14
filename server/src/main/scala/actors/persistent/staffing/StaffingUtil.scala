package actors.persistent.staffing

import drt.shared._
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

object StaffingUtil {
  def generateDailyAssignments(shift: Shift): Seq[StaffAssignment] = {
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
      val startMillis = SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, startHH, startMM, europeLondonTimeZone).millisSinceEpoch

      val isShiftEndAfterMidNight = endHH < startHH || (endHH == startHH && endMM < startMM)
      val endMillis = if (isShiftEndAfterMidNight) {
        SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
          europeLondonTimeZone).addDays(1).millisSinceEpoch
      } else {
        SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
          europeLondonTimeZone).millisSinceEpoch
      }

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

  def updateWithAShiftDefaultStaff(previousShift: Shift, overridingShift: Seq[StaffShiftRow], shift: Shift, allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {
    val allShiftsStaff: Seq[StaffAssignment] = generateDailyAssignments(shift)
    println(s"...previousShift :${previousShift}  shift :$shift")
    val splitDailyAssignmentsWithOverlap = allShiftsStaff
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .groupBy(a => TM(a.terminal, a.start))
      .map { case (tm, assignments) =>
        val combinedAssignment = assignments.reduce { (a1, a2) =>
          a1.copy(numberOfStaff = a1.numberOfStaff + a2.numberOfStaff)
        }
        tm -> combinedAssignment
      }

    val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap

    val overriddingShiftAssingments = overridingShift.flatMap { os =>
      generateDailyAssignments(ShiftUtil.fromStaffShiftRow(os))
    }.map(a => TM(a.terminal, a.start) -> a).toMap

    def findOverridingShift(assignment: StaffAssignment): Int = {
      overriddingShiftAssingments.get(TM(assignment.terminal, assignment.start)).map(_.numberOfStaff).getOrElse(0)
    }

    splitDailyAssignmentsWithOverlap.map {
      case (tm, assignment) =>
        existingAllAssignments.get(tm) match {
          case Some(existing) =>
            val isPrevStaff = existing.numberOfStaff == previousShift.staffNumber
            if ((existing.numberOfStaff == 0) || isPrevStaff)
              assignment
            else {
              // existing
             val overridingStaff = findOverridingShift(assignment)
             if (existing.numberOfStaff == overridingStaff + previousShift.staffNumber ||
                 existing.numberOfStaff == overridingStaff + shift.staffNumber)
               assignment.copy(numberOfStaff = overridingStaff + shift.staffNumber)
             else existing
            }
          case None => assignment
        }
    }.toSeq.sortBy(_.start)
  }

  def updateWithShiftDefaultStaff(shifts: Seq[Shift], allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {

    val allShiftsStaff: Seq[StaffAssignment] = shifts.flatMap { shift =>
      generateDailyAssignments(shift)
    }

    val splitDailyAssignmentsWithOverlap = allShiftsStaff
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .groupBy(a => TM(a.terminal, a.start))
      .map { case (tm, assignments) =>
        val combinedAssignment = assignments.reduce { (a1, a2) =>
          a1.copy(numberOfStaff = a1.numberOfStaff + a2.numberOfStaff)
        }
        tm -> combinedAssignment
      }

    val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap

    splitDailyAssignmentsWithOverlap.map {
      case (tm, assignment) =>
        existingAllAssignments.get(tm) match {
          case Some(existing) =>
            if (existing.numberOfStaff == 0) assignment else existing
          case None => assignment
        }
    }.toSeq.sortBy(_.start)

  }

}
