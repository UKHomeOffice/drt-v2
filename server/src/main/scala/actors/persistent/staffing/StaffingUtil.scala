package actors.persistent.staffing

import drt.shared._
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil.convertToSqlDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

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

  def updateWithAShiftDefaultStaff(previousShift: Shift, overridingShift: Seq[StaffShiftRow], newShift: Shift, allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {
    val newShiftsStaff: Seq[StaffAssignment] = generateDailyAssignments(newShift)
    println(s"...previousShift :${previousShift}  shift :$newShift")
    val newShiftSplitDailyAssignments: Map[TM, StaffAssignment] = newShiftsStaff
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .groupBy(a => TM(a.terminal, a.start))
      .map { case (tm, assignments) =>
        val combinedAssignment = assignments.reduce { (a1, a2) =>
          a1.copy(numberOfStaff = a1.numberOfStaff + a2.numberOfStaff)
        }
        tm -> combinedAssignment
      }

    val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap

    val overidingAssignments = overridingShift
      .filterNot(s => s.port == newShift.port && s.terminal == newShift.terminal && s.shiftName == newShift.shiftName && s.startDate == convertToSqlDate(newShift.startDate)).flatMap { os =>
        generateDailyAssignments(ShiftUtil.fromStaffShiftRow(os))
      }

    val overriddingShiftAssingments: Map[TM, StaffAssignment] =
      overidingAssignments.flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
        .groupBy(a => TM(a.terminal, a.start))
        .map { case (tm, assignments) =>
          val combinedAssignment = assignments.reduce { (a1, a2) =>
            a1.copy(numberOfStaff = a1.numberOfStaff + a2.numberOfStaff)
          }
          tm -> combinedAssignment
        }


    println(s"overriddingShiftAssingments: ${overriddingShiftAssingments.size}")
    overriddingShiftAssingments.map((a => println(SDate(a._1.minute).prettyDateTime, a._2)))

    def findOverridingShift(assignment: StaffAssignment): Int = {
      overriddingShiftAssingments.get(TM(assignment.terminal, assignment.start)).map(_.numberOfStaff).getOrElse(0)
    }

    newShiftSplitDailyAssignments.map {
      case (tm, assignment) =>
        existingAllAssignments.get(tm) match {
          case Some(existing) =>
            val isPrevStaff = previousShift.staffNumber == existing.numberOfStaff
            if ((existing.numberOfStaff == 0) || isPrevStaff)
              assignment
            else {
              // existing
              val overridingStaff = findOverridingShift(assignment)
              println(s"assignment : $assignment, existing: $existing, overridingStaff: $overridingStaff")
              println(s"overridingStaff: $overridingStaff, existing: ${existing.numberOfStaff}, previousShift.staffNumber: ${previousShift.staffNumber}, shift.staffNumber: ${newShift.staffNumber}")
              if (existing.numberOfStaff == overridingStaff + previousShift.staffNumber)
                assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
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
