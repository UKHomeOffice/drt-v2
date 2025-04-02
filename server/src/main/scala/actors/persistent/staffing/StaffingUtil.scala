package actors.persistent.staffing

import drt.shared.{Shift, ShiftAssignments, StaffAssignment, StaffAssignmentLike, TM}
import org.joda.time.DateTimeZone
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonId
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}
import java.util.TimeZone

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
      val startMillis = SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, startHH, startMM,
        DateTimeZone.forTimeZone(TimeZone.getTimeZone(europeLondonId))).millisSinceEpoch

      val isShiftEndAfterMidNight = endHH < startHH || (endHH == startHH && endMM < startMM)
      val endMillis = if (isShiftEndAfterMidNight) {
        SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
          DateTimeZone.forTimeZone(TimeZone.getTimeZone(europeLondonId))).addDays(1).millisSinceEpoch
      } else {
        SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
          DateTimeZone.forTimeZone(TimeZone.getTimeZone(europeLondonId))).millisSinceEpoch
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
