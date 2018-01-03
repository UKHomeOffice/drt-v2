package services.shifts

import drt.shared.{SDateLike, StaffTimeSlotsForMonth}
import services.SDate

import scala.util.{Success, Try}

object StaffTimeSlots {

  def slotsToShifts(slots: StaffTimeSlotsForMonth) = {
    slots.timeSlots.filter(_.staff != 0).zipWithIndex.map {
      case (slot, index) =>
        val dateTime = SDate(slot.start)
        f"shift${slots.month.getMonth()}%02d${slots.month.getFullYear()}$index, ${slot.terminal}, ${dateTime.ddMMyyString}, ${dateTime.prettyTime}, ${dateTime.addMillis(slot.durationMillis - 1).prettyTime}, ${slot.staff}"
    }.mkString("\n")
  }

  def removeMonthFromShifts(shifts: String, date: SDateLike) = {
    val twoDigitYear = date.getFullYear().toString.substring(2, 4)
    val filterDate2DigitYear = f"${date.getDate()}%02d/${date.getMonth()}%02d/$twoDigitYear"
    val filterDate4DigitYear = f"${date.getDate()}%02d/${date.getMonth()}%02d/${date.getFullYear()}"
    val todaysShifts = shifts.split("\n").filter(l => {
      l.contains(filterDate2DigitYear) || l.contains(filterDate4DigitYear)
    })
  }

  def isDateInMonth(dateString: String, month: SDateLike) = {
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

  def replaceShiftMonthWithTimeSlotsForMonth(existingShifts: String, slots: StaffTimeSlotsForMonth) = {
    val shiftsExcludingNewMonth = existingShifts.split("\n")
      .filter(line => {
        line.replaceAll("([^\\\\]),", "$1\",\"")
          .split("\",\"").toList.map(_.trim) match {
          case List(_, _, d, _, _, _) =>
            !isDateInMonth(d, slots.month)
          case _ => false
        }
      })

    (shiftsExcludingNewMonth.mkString("\n") + "\n" + StaffTimeSlots.slotsToShifts(slots)).trim
  }
}
