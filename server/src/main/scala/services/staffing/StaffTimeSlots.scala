package services.staffing

import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.util.{Success, Try}

object StaffTimeSlots {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  private def slotsToShiftsAssignments(slots: StaffTimeSlotsForTerminalMonth): Seq[StaffAssignment] = {
    val monthSDate = SDate(slots.monthMillis)
    slots.timeSlots.filter(_.staff != 0).zipWithIndex.map {
      case (slot, index) =>
        val dateTime = SDate(slot.start)
        val name = f"shift${monthSDate.getMonth}%02d${monthSDate.getFullYear}$index"
        val startMilli = SDate(dateTime.millisSinceEpoch)
        val endMilli = startMilli.addMillis(slot.durationMillis - 60000)
        StaffAssignment(name, slot.terminal, startMilli.millisSinceEpoch, endMilli.millisSinceEpoch, slot.staff, None)
    }
  }

  def isDateInMonth(dateString: String, month: SDateLike): Boolean = {
    val ymd = dateString.split("/").toList

    Try((ymd.head.toInt, ymd(1).toInt, ymd(2).toInt)) match {
      case Success((_, m, y)) if month.getMonth == m && month.getFullYear == y =>
        true
      case Success((_, m, y)) if month.getMonth == m && month.getFullYear - 2000 == y =>
        true
      case _ =>
        false
    }
  }

  def replaceShiftMonthWithTimeSlotsForMonth(existingShifts: ShiftAssignments, slots: StaffTimeSlotsForTerminalMonth): ShiftAssignments = {
    val slotSdate = SDate(slots.monthMillis, Crunch.europeLondonTimeZone)

    val shiftsExcludingNewMonth = existingShifts
      .assignments
      .filterNot(assignment => {
        val assignmentSdate = SDate(assignment.start, Crunch.europeLondonTimeZone)
        val sameMonth = assignmentSdate.getMonth == slotSdate.getMonth
        val sameYear = assignmentSdate.getFullYear == slotSdate.getFullYear
        val sameTerminal = assignment.terminal == slots.terminalName
        sameMonth && sameYear && sameTerminal
      })

    ShiftAssignments(StaffTimeSlots.slotsToShiftsAssignments(slots) ++ shiftsExcludingNewMonth)
  }

  def getShiftsForMonth(shifts: ShiftAssignments, month: SDateLike): ShiftAssignments = {
    val assignmentsForMonth = shifts.assignments
      .filter(assignment => {
        val assignmentSdate = SDate(assignment.start, Crunch.europeLondonTimeZone)
        assignmentSdate.getMonth == month.getMonth && assignmentSdate.getFullYear == month.getFullYear
      })

    ShiftAssignments(assignmentsForMonth)
  }
}
