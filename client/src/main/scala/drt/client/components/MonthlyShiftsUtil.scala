package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.{ShiftAssignments, StaffAssignmentLike, StaffShift}
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

  def assignmentsForShift(firstDay: SDateLike, daysCount: Int, interval: Int, terminal: Terminal, s: StaffShift, shifts: ShiftAssignments): Seq[ShiftAssignment] = {
    val Array(startHour, startMinute) = s.startTime.split(":").map(_.toInt)
    val Array(endHour, endMinute) = s.endTime.split(":").map(_.toInt)
    var nextDay = firstDay

    (1 to daysCount).flatMap { day =>
      val start = SDate(nextDay.getFullYear, nextDay.getMonth, nextDay.getDate, startHour, startMinute)
      val end = SDate(nextDay.getFullYear, nextDay.getMonth, nextDay.getDate, endHour, endMinute)
      val dayAssignments: Seq[StaffAssignmentLike] = shifts.assignments.filter(assignment => assignment.start >= start.millisSinceEpoch && assignment.end <= end.millisSinceEpoch)
      nextDay = nextDay.addDays(1)

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
            ShiftAssignment(
              column = day,
              row = index + 1,
              name = sa.name,
              staffNumber = sa.numberOfStaff,
              startTime = ShiftDate(currentTime.getFullYear, currentTime.getMonth, currentTime.getDate, currentTime.getHours, currentTime.getMinutes),
              endTime = ShiftDate(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
            )
          case None =>
            ShiftAssignment(
              column = day,
              row = index + 1,
              name = s.shiftName,
              staffNumber = s.staffNumber,
              startTime = ShiftDate(currentTime.getFullYear, currentTime.getMonth, currentTime.getDate, currentTime.getHours, currentTime.getMinutes),
              endTime = ShiftDate(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
            )
        }
      }
    }
  }

  def generateShiftData(viewingDate: SDateLike, dayRange: String, terminal: Terminal, staffShifts: Seq[StaffShift], shifts: ShiftAssignments, interval: Int): Seq[ShiftData] = {
    staffShifts.sortBy(_.startTime).zipWithIndex.map { case (s, index) =>
      ShiftData(
        index = index,
        defaultShift = DefaultShift(s.shiftName, s.staffNumber, s.startTime, s.endTime),
        assignments = assignmentsForShift(firstDay(dayRange, viewingDate), daysCount(dayRange, viewingDate), interval, terminal, s, shifts)
      )
    }
  }

  private def toStringShiftDate(shiftDate: ShiftDate) = {
    s"${shiftDate.year}-${shiftDate.month}-${shiftDate.day} ${shiftDate.hour}:${shiftDate.minute}"
  }

  def updateChangeAssignment(previousChange: Seq[ShiftAssignment], newChange: Seq[ShiftAssignment]): Seq[ShiftAssignment] = {
    val previousChangeMap = previousChange.map(a => (toStringShiftDate(a.startTime)) -> a).toMap
    val newChangeMap = newChange.map(a => (toStringShiftDate(a.startTime)) -> a).toMap
    val mergedMap = previousChangeMap ++ newChangeMap
    mergedMap.values.toSeq
  }

  def updateAssignments(shifts: Seq[ShiftData], changedAssignments: Seq[ShiftAssignment], slotMinutes: Int): Seq[ShiftData] = {
    val changedAssignmentsWithSlotMap: Seq[ShiftAssignment] = changedAssignments.flatMap(a => ShiftAssignment.splitIntoSlots(a, slotMinutes))
    val changedAssignmentsMap: Map[String, ShiftAssignment] = changedAssignmentsWithSlotMap.map(a => (toStringShiftDate(a.startTime)) -> a).toMap
    shifts.map { shift: ShiftData =>
      val updatedAssignments = shift.assignments.map { assignment =>
        changedAssignmentsMap.getOrElse(toStringShiftDate(assignment.startTime), assignment)
      }
      ShiftData(shift.index, shift.defaultShift, updatedAssignments.toSeq)
    }
  }
}
