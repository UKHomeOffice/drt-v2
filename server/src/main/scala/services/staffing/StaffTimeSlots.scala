package services.staffing

import drt.shared.FlightsApi.TerminalName
import drt.shared._
import services.SDate
import services.graphstages.Crunch

import scala.util.{Success, Try}

object StaffTimeSlots {

  def slotsToShifts(slots: StaffTimeSlotsForTerminalMonth): String = {
    val monthSDate = SDate(slots.monthMillis)
    slots.timeSlots.filter(_.staff != 0).zipWithIndex.map {
      case (slot, index) =>
        val dateTime = SDate(slot.start)
        f"shift${monthSDate.getMonth()}%02d${monthSDate.getFullYear()}$index, ${slot.terminal}, ${dateTime.ddMMyyString}, ${dateTime.prettyTime()}, ${dateTime.addMillis(slot.durationMillis - 1).prettyTime}, ${slot.staff}"
    }.mkString("\n")
  }

  def slotsToShiftsAssignments(slots: StaffTimeSlotsForTerminalMonth): Seq[StaffAssignment] = {
    val monthSDate = SDate(slots.monthMillis)
    slots.timeSlots.filter(_.staff != 0).zipWithIndex.map {
      case (slot, index) =>
        val dateTime = SDate(slot.start)
        val name = s"shift${monthSDate.getMonth()}%02d${monthSDate.getFullYear()}$index"
        val startMilli = SDate(dateTime.millisSinceEpoch)
        val endMilli = startMilli.addMillis(slot.durationMillis - 1)
        StaffAssignment(name, slot.terminal, MilliDate(startMilli.millisSinceEpoch), MilliDate(endMilli.millisSinceEpoch), slot.staff, None)
    }
  }

  def isDateInMonth(dateString: String, month: SDateLike): Boolean = {
    val ymd = dateString.split("/").toList

    Try((ymd(0).toInt, ymd(1).toInt, ymd(2).toInt)) match {
      case Success((d, m, y)) if month.getMonth == m && month.getFullYear() == y =>
        true
      case Success((d, m, y)) if month.getMonth == m && month.getFullYear() - 2000 == y =>
        true
      case other =>
        false
    }
  }

  def replaceShiftMonthWithTimeSlotsForMonth(existingShifts: StaffAssignments, slots: StaffTimeSlotsForTerminalMonth): StaffAssignments = {
    val shiftsExcludingNewMonth = existingShifts
      .assignments
      .filterNot(assignment => {
        val assignmentSdate = SDate(assignment.startDt.millisSinceEpoch, Crunch.europeLondonTimeZone)
        val slotSdate = SDate(slots.monthMillis, Crunch.europeLondonTimeZone)

        assignmentSdate.getMonth() == slotSdate.getMonth() && assignmentSdate.getFullYear() == slotSdate.getFullYear()
      })

    StaffAssignments(StaffTimeSlots.slotsToShiftsAssignments(slots) ++ shiftsExcludingNewMonth)
  }

  private def shiftLineToFieldList(line: String) = {
    line.replaceAll("([^\\\\]),", "$1\",\"")
      .split("\",\"").toList.map(_.trim)
  }

  private def shiftsToLines(existingShifts: String) = {
    existingShifts.split("\n")
  }

  def getShiftsForMonth(shifts: String, month: SDateLike, terminalName: TerminalName): String = {
    shiftsToLines(shifts)
      .filter(line => {
        shiftLineToFieldList(line) match {
          case List(_, tn, d, _, _, _) =>
            tn == terminalName && isDateInMonth(d, month)
          case _ => false
        }
      })
      .zipWithIndex
      .map {
        case (line, idx) => {
          val fields = shiftLineToFieldList(line).drop(1)
          (idx.toString :: fields).mkString(",")
        }
      }
      .mkString("\n")
  }
}
