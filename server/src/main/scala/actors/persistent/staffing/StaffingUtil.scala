package actors.persistent.staffing

import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil.{convertToSqlDate, safeSDate}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

object StaffingUtil {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def generateDailyAssignments(shift: Shift): Seq[StaffAssignment] = {
    val (startHH, startMM) = shift.startTime.split(":") match {
      case Array(hh, mm) => (hh.toInt, mm.toInt)
    }
    val (endHH, endMM) = shift.endTime.split(":") match {
      case Array(hh, mm) => (hh.toInt, mm.toInt)
    }

    val startDate = SDate(shift.startDate.year, shift.startDate.month, shift.startDate.day, 0, 0, europeLondonTimeZone)
    val endDate = shift.endDate.map(ed => SDate(ed.year, ed.month, ed.day, 0, 0, europeLondonTimeZone)).getOrElse(startDate.addMonths(6))

    val daysBetween = startDate.daysBetweenInclusive(endDate) - 1
    (0 to daysBetween).map { day =>
      val currentDate: SDateLike = startDate.addDays(day)
      val startMillis = safeSDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, startHH, startMM, europeLondonTimeZone).millisSinceEpoch
      val isShiftEndAfterMidNight = endHH < startHH || (endHH == startHH && endMM < startMM)
      val endMillis = if (isShiftEndAfterMidNight) {
        safeSDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
          europeLondonTimeZone).addDays(1).millisSinceEpoch
      } else {
        safeSDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
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

  private def combinedAssignments(shiftAssignments: Seq[StaffAssignment]): Map[TM, StaffAssignment] =
    shiftAssignments
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .groupBy(a => TM(a.terminal, a.start))
      .map { case (tm, assignments) =>
        val combinedAssignment = assignments.reduce { (a1, a2) =>
          a1.copy(numberOfStaff = a1.numberOfStaff + a2.numberOfStaff)
        }
        tm -> combinedAssignment
      }

  def updateWithAShiftDefaultStaff(previousShift: Shift, overridingShift: Seq[StaffShiftRow], newShift: Shift, allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {
    val newShiftsStaff: Seq[StaffAssignment] = generateDailyAssignments(newShift)
    val newShiftSplitDailyAssignments: Map[TM, StaffAssignment] = combinedAssignments(newShiftsStaff)

    val overidingAssignments = overridingShift
      .filterNot(s => s.port == newShift.port && s.terminal == newShift.terminal && s.shiftName == newShift.shiftName && s.startDate == convertToSqlDate(newShift.startDate)).flatMap { os =>
        generateDailyAssignments(ShiftUtil.fromStaffShiftRow(os))
      }

    val overriddingShiftAssingments: Map[TM, StaffAssignment] = combinedAssignments(overidingAssignments)

    val isTimeChange = previousShift.startTime != newShift.startTime || previousShift.endTime != newShift.endTime

    def findOverridingShift(assignment: StaffAssignment): Int = {
      overriddingShiftAssingments.get(TM(assignment.terminal, assignment.start)).map(_.numberOfStaff).getOrElse(0)
    }

    val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap

    val updatedNewShiftsAssignments = newShiftSplitDailyAssignments.map {
      case (tm, assignment) =>
        existingAllAssignments.get(tm) match {
          case Some(existing) =>
            val overridingStaff = findOverridingShift(assignment)
            if (existing.numberOfStaff == overridingStaff + previousShift.staffNumber || isTimeChange)
              assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
            else
              existing

          case None => assignment
        }
    }.toSeq.sortBy(_.start)


    def timeChangeMerge() = {
      val timeChangedOverridingShiftAssignments: Seq[StaffAssignmentLike] = overriddingShiftAssingments.map { case (tm, overridingAssignment) =>
        newShiftSplitDailyAssignments.get(tm) match {
          case Some(newShiftAssignment) =>
            updatedNewShiftsAssignments.find(a => a.terminal == newShiftAssignment.terminal && a.start == newShiftAssignment.start) match {
              case Some(updatedAssignments) =>
                updatedAssignments
              case None =>
                newShiftAssignment
            }
          case None =>
            existingAllAssignments.get(tm) match {
              case Some(existingAssignment) =>
                if (existingAssignment.numberOfStaff > overridingAssignment.numberOfStaff)
                  overridingAssignment
                else
                  existingAssignment
              case None =>
                overridingAssignment
            }
        }
      }.toSeq

      val merged = {
        val timeChangedMap = timeChangedOverridingShiftAssignments.map(a => TM(a.terminal, a.start) -> a).toMap
        val newShiftMap = updatedNewShiftsAssignments.map(a => TM(a.terminal, a.start) -> a).toMap
        val allKeys = timeChangedMap.keySet ++ newShiftMap.keySet

        allKeys.toSeq.sorted.map { tm =>
          timeChangedMap.getOrElse(tm, newShiftMap(tm))
        }
      }
      merged
    }

    if (isTimeChange)
      timeChangeMerge()
    else
      updatedNewShiftsAssignments
  }

  def updateWithShiftDefaultStaff(shifts: Seq[Shift], allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {

    val allShiftsStaff: Seq[StaffAssignment] = shifts.flatMap { shift =>
      generateDailyAssignments(shift)
    }

    val splitDailyAssignmentsWithOverlap = combinedAssignments(allShiftsStaff)

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
