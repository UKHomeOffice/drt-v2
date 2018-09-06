package services.staffing

import drt.shared.FlightsApi.TerminalName
import drt.shared.{SDateLike, StaffTimeSlotsForTerminalMonth}
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

  def replaceShiftMonthWithTimeSlotsForMonth(existingShifts: String, slots: StaffTimeSlotsForTerminalMonth): TerminalName = {
    val shiftsExcludingNewMonth = shiftsToLines(existingShifts)
      .filter(line => {
        shiftLineToFieldList(line) match {
          case List(_, t, d, _, _, _) if !isDateInMonth(d, SDate(slots.monthMillis, Crunch.europeLondonTimeZone)) || t != slots.terminal => true
          case _ => false
        }
      })

    (shiftsExcludingNewMonth.mkString("\n") + "\n" + StaffTimeSlots.slotsToShifts(slots)).trim
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
