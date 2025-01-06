package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.{ShiftAssignments, StaffAssignment, StaffShift}
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
    var nextDay = firstDay
    (1 to daysCount).flatMap { day =>
      val Array(startHour, startMinute) = s.startTime.split(":").map(_.toInt)
      val Array(endHour, endMinute) = s.endTime.split(":").map(_.toInt)
      val start = SDate(nextDay.getFullYear, nextDay.getMonth, nextDay.getDate, startHour, startMinute).millisSinceEpoch
      val end = SDate(nextDay.getFullYear, nextDay.getMonth, nextDay.getDate, endHour, endMinute).millisSinceEpoch
      val assignments: Seq[StaffAssignment] = StaffAssignment(s.shiftName, terminal, start, end, s.staffNumber, None).splitIntoSlots(interval).sortBy(_.start)
      nextDay = nextDay.addDays(1)
      assignments.zipWithIndex.map { case (a, index) =>
        val startTime = SDate(a.start)
        val endTime = SDate(a.end)
        val matchingAssignments = shifts.assignments.filter(sa => sa.start >= a.start && sa.end <= a.end).sortBy(_.start)
        matchingAssignments.headOption match {
          case Some(sa) =>
            ShiftAssignment(
              column = day,
              row = index + 1, // Start rowId from 1
              name = sa.name,
              staffNumber = sa.numberOfStaff,
              startTime = ShiftDate(startTime.getFullYear, startTime.getMonth, startTime.getDate, startTime.getHours, startTime.getMinutes),
              endTime = ShiftDate(endTime.getFullYear, endTime.getMonth, endTime.getDate, endTime.getHours, endTime.getMinutes)
            )
          case None =>
            ShiftAssignment(
              column = day,
              row = index + 1, // Start rowId from 1
              name = s.shiftName,
              staffNumber = a.numberOfStaff,
              startTime = ShiftDate(startTime.getFullYear, startTime.getMonth, startTime.getDate, startTime.getHours, startTime.getMinutes),
              endTime = ShiftDate(endTime.getFullYear, endTime.getMonth, endTime.getDate, endTime.getHours, endTime.getMinutes)
            )
        }
      }
    }
  }

  def generateShiftData(viewingDate: SDateLike, dayRange: String, terminal: Terminal, staffShifts: Seq[StaffShift], shifts: ShiftAssignments, interval: Int): Seq[ShiftData] = {
    staffShifts.zipWithIndex.map { case (s, index) =>
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

  def updateAssignments(shifts: Seq[ShiftData], changedAssignments: Seq[ShiftAssignment]): Seq[ShiftData] = {
    val changedAssignmentsMap = changedAssignments.map(a => (toStringShiftDate(a.startTime)) -> a).toMap
    //    println(s"changedAssignmentsMap: ${changedAssignmentsMap.map { case (k, v) => s"$k -> ${v.staffNumber}" }}")
    shifts.map { shift =>
      //      println(s"shift: ${shift.index} ${shift.defaultShift.name}")
      val updatedAssignments = shift.assignments.map { assignment =>
        //        println(s"startTime: ${toStringShiftDate(assignment.startTime)}, key: ${toStringShiftDate(assignment.startTime)}, value: ${changedAssignmentsMap.get(toStringShiftDate(assignment.startTime)).map(_.staffNumber)}")
        changedAssignmentsMap.getOrElse(toStringShiftDate(assignment.startTime), assignment)
      }

      //      updatedAssignments.foreach { assignment =>
      //        println(s"assignment: ${assignment.startTime} ${assignment.staffNumber}")
      //      }
      ShiftData(shift.index, shift.defaultShift, updatedAssignments.toSeq)
    }
  }
}
