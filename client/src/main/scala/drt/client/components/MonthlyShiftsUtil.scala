package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.{ShiftAssignments, StaffAssignmentLike, Shift}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike
import scala.scalajs.js.Date


object MonthlyShiftsUtil {

  val numberOfDaysInMonth: SDateLike => Int = { viewingDate: SDateLike =>
    def daysInMonth(year: Int, month: Int): Int = {
      val date = new Date(year, month, 0)
      date.getDate().toInt
    }

    daysInMonth(viewingDate.getFullYear, viewingDate.getMonth)
  }

  val daysCount: (String, SDateLike) => Int = { (dayRange, viewingDate) =>
    dayRange match {
      case "weekly" => 7
      case "daily" => 1
      case _ => numberOfDaysInMonth(viewingDate)
    }
  }

  val firstDay: (String, SDateLike) => SDateLike = { (dayRange, viewingDate) =>
    dayRange match {
      case "weekly" =>
        SDate.firstDayOfWeek(viewingDate)
      case "daily" => viewingDate
      case _ => SDate.firstDayOfMonth(viewingDate)
    }
  }

  def iteratorForShiftAssignment(isShiftEndAfterMidNight: Boolean,
                                 day: Int,
                                 start: SDateLike,
                                 end: SDateLike,
                                 endHour: Int,
                                 endMinute: Int,
                                 isFirstDayForShiftEndAfterMidNight: Boolean,
                                 addToIndex: Int,
                                 interval: Int,
                                 terminal: Terminal, s: Shift,
                                 shifts: ShiftAssignments) = {
    val dayAssignments: Seq[StaffAssignmentLike] = shifts.assignments.filter(assignment => assignment.start >= start.millisSinceEpoch && assignment.end <= end.millisSinceEpoch)
    Iterator.iterate(start) { intervalTime =>
      if ((intervalTime.getMinutes == 30 && interval == 60) || (intervalTime.getHours == endHour && interval == 60 && endMinute == 30)) {
        intervalTime.addMinutes(30)
      } else {
        intervalTime.addMinutes(interval)
      }
    }.takeWhile(_ < end).toSeq.zipWithIndex.map { case (currentTime, index) =>
      val nextTime = if ((currentTime.getMinutes == 30 && interval == 60) || (currentTime.getHours == endHour && interval == 60 && endMinute == 30)) {
        currentTime.addMinutes(30)
      } else {
        currentTime.addMinutes(interval)
      }
      val matchedAssignments = dayAssignments.find(assignment => assignment.start == currentTime.millisSinceEpoch && assignment.terminal == terminal)
      matchedAssignments match {
        case Some(sa) =>
          StaffTableEntry(
            column = day,
            row = if (isShiftEndAfterMidNight) index + addToIndex else index,
            name = sa.name,
            staffNumber = sa.numberOfStaff,
            startTime = ShiftDate(currentTime.getFullYear, currentTime.getMonth, currentTime.getDate, currentTime.getHours, currentTime.getMinutes),
            endTime = ShiftDate(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
          )
        case None =>
          StaffTableEntry(
            column = day,
            row = if (isShiftEndAfterMidNight) index + addToIndex else index,
            name = s.shiftName,
            staffNumber = if (isFirstDayForShiftEndAfterMidNight) 0 else s.staffNumber,
            startTime = ShiftDate(currentTime.getFullYear, currentTime.getMonth, currentTime.getDate, currentTime.getHours, currentTime.getMinutes),
            endTime = ShiftDate(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
          )
      }
    }
  }

  def assignmentsForShift(firstDay: SDateLike, daysCount: Int, interval: Int, terminal: Terminal, s: Shift, shifts: ShiftAssignments): Seq[StaffTableEntry] = {
    val Array(startHour, startMinute) = s.startTime.split(":").map(_.toInt)
    val Array(endHour, endMinute) = s.endTime.split(":").map(_.toInt)
    var nextDay = firstDay

    val isShiftEndAfterMidNight = endHour < startHour || (endHour == startHour && endMinute < startMinute)

    (1 to daysCount).flatMap { day =>
      val start = SDate(nextDay.getFullYear, nextDay.getMonth, nextDay.getDate, startHour, startMinute)

      val end = SDate(nextDay.getFullYear, nextDay.getMonth, nextDay.getDate, endHour, endMinute)

      val beforeMidnight = iteratorForShiftAssignment(isShiftEndAfterMidNight,
        day, start, if (isShiftEndAfterMidNight) SDate(end.getFullYear, end.getMonth, end.getDate, 0, 0).addDays(1) else end, endHour, endMinute,
        isFirstDayForShiftEndAfterMidNight = false, 0, interval, terminal, s, shifts)
      val afterMightNight = if (isShiftEndAfterMidNight) {
        val start = SDate(nextDay.getFullYear, nextDay.getMonth, nextDay.getDate, 0, 0)
        iteratorForShiftAssignment(isShiftEndAfterMidNight, day, start, end, endHour, endMinute,
          isFirstDayForShiftEndAfterMidNight = true, beforeMidnight.size, interval, terminal, s, shifts)
      } else Seq.empty

      nextDay = nextDay.addDays(1)
      beforeMidnight ++ afterMightNight
    }
  }

  def generateShiftData(viewingDate: SDateLike, dayRange: String, terminal: Terminal, staffShifts: Seq[Shift], shifts: ShiftAssignments, interval: Int): Seq[ShiftSummaryStaffing] = {
    staffShifts.sortBy(_.startTime).zipWithIndex.map { case (s, index) =>
      ShiftSummaryStaffing(
        index = index,
        shiftSummary = ShiftSummary(s.shiftName, s.staffNumber, s.startTime, s.endTime),
        assignments = assignmentsForShift(firstDay(dayRange, viewingDate), daysCount(dayRange, viewingDate), interval, terminal, s, shifts)
      )
    }
  }

  def updateChangeAssignment(previousChange: Seq[StaffTableEntry], newChange: Seq[StaffTableEntry]): Seq[StaffTableEntry] = {
    val previousChangeMap = previousChange.map(a => (ShiftDate.toString(a.startTime)) -> a).toMap
    val newChangeMap = newChange.map(a => (ShiftDate.toString(a.startTime)) -> a).toMap
    val mergedMap = previousChangeMap ++ newChangeMap
    mergedMap.values.toSeq
  }

  def updateAssignments(shifts: Seq[ShiftSummaryStaffing], changedAssignments: Seq[StaffTableEntry], slotMinutes: Int): Seq[ShiftSummaryStaffing] = {
    val changedAssignmentsWithSlotMap: Seq[StaffTableEntry] = changedAssignments.flatMap(a => StaffTableEntry.splitIntoSlots(a, slotMinutes))
    val changedAssignmentsMap: Map[String, StaffTableEntry] = changedAssignmentsWithSlotMap.map(a => (ShiftDate.toString(a.startTime)) -> a).toMap
    shifts.map { shift: ShiftSummaryStaffing =>
      val updatedAssignments = shift.staffTableEntries.map { assignment =>
        changedAssignmentsMap.getOrElse(ShiftDate.toString(assignment.startTime), assignment)
      }
      ShiftSummaryStaffing(shift.index, shift.shiftSummary, updatedAssignments.toSeq)
    }
  }
}
