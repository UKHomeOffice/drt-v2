package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.scalajs.js.Date


object MonthlyShiftsUtil {

  case class ShiftDetails(shift: Shift,
                          terminal: Terminal,
                          shiftAssignments: ShiftAssignments)

  case class ShiftPeriod(start: SDateLike,
                         end: SDateLike,
                         endHour: Int,
                         endMinute: Int,
                         intervalMinutes: Int,
                         day: Int,
                         endsAfterMidnight: Boolean,
                         firstDayEndsAfterMidnight: Boolean,
                         addToIndex: Int)

  val numberOfDaysInMonth: SDateLike => Int = date =>
    new Date(date.getFullYear, date.getMonth, 0).getDate().toInt

  val daysCountByViewPeriod: (String, SDateLike) => Int = (viewPeriod, viewingDate) =>
    viewPeriod match {
      case "weekly" => 7
      case "daily" => 1
      case _ => numberOfDaysInMonth(viewingDate)
    }

  val firstDayByViewPeriod: (String, SDateLike) => LocalDate = (viewPeriod, viewingDate) =>
    viewPeriod match {
      case "weekly" => SDate.firstDayOfWeek(viewingDate).toLocalDate
      case "daily" => viewingDate.toLocalDate
      case _ => SDate.firstDayOfMonth(viewingDate).toLocalDate
    }

  def createStaffTableEntries(startDate: LocalDate,
                              daysCount: Int,
                              intervalMinutes: Int,
                              shiftDetails: ShiftDetails,
                             ): Seq[StaffTableEntry] = {

    val dayRange = dayRangeForView(startDate, daysCount, shiftDetails)

    val Array(shiftStartHour, shiftStartMinute) = shiftDetails.shift.startTime.split(":").map(_.toInt)
    val Array(shiftEndHour, shiftEndMinute) = shiftDetails.shift.endTime.split(":").map(_.toInt)

    val shiftEndsAfterMidnight = shiftEndHour < shiftStartHour || (shiftEndHour == shiftStartHour && shiftEndMinute < shiftStartMinute)
    //For all the days in the period, create the staff table entries for the shift
    (dayRange.start to dayRange.end).flatMap { day =>
      val currentDay = SDate(startDate).addDays(day - 1).toLocalDate
      val shiftStartTime = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, shiftStartHour, shiftStartMinute)
      val shiftEndTime = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, shiftEndHour, shiftEndMinute)
      val midnightNextDay = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, 0, 0).addDays(1)

      val beforeMidnightPeriod = ShiftPeriod(
        start = shiftStartTime,
        end = if (shiftEndsAfterMidnight) midnightNextDay else shiftEndTime,
        endHour = shiftEndHour,
        endMinute = shiftEndMinute,
        intervalMinutes = intervalMinutes,
        day = day,
        endsAfterMidnight = shiftEndsAfterMidnight,
        firstDayEndsAfterMidnight = false,
        addToIndex = 0
      )

      val entriesBeforeMidnight = staffTableEntriesForShift(beforeMidnightPeriod, shiftDetails)

      val isFirstNightShiftForMonth = day == 1 && shiftEndsAfterMidnight && daysCount > 7

      val entriesFromMidnight =
        if (shiftEndsAfterMidnight) {
          getEntriesAfterMidnight(shiftDetails, shiftEndHour, shiftEndMinute, currentDay, beforeMidnightPeriod, entriesBeforeMidnight, isFirstNightShiftForMonth)
        }
        else Seq.empty

      entriesBeforeMidnight ++ entriesFromMidnight
    }
  }

  private def getEntriesAfterMidnight(shiftDetails: ShiftDetails, shiftEndHour: Int, shiftEndMinute: Int, currentDay: SDateLike, beforeMidnightPeriod: ShiftPeriod, entriesBeforeMidnight: Seq[StaffTableEntry], isFirstNightShiftForMonth: Boolean) = {
    val midnightStart = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, 0, 0)
    val endTime = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, shiftEndHour, shiftEndMinute)

    val periodFromMidnight = beforeMidnightPeriod.copy(
      start = midnightStart,
      end = endTime,
      firstDayEndsAfterMidnight = isFirstNightShiftForMonth,
      addToIndex = entriesBeforeMidnight.size
    )

    staffTableEntriesForShift(periodFromMidnight, shiftDetails)
  }

  private def dayRangeForView(startDate: LocalDate, daysCount: Int, shiftDetails: ShiftDetails): Range.Inclusive = {
    val startDay: Int = if (startDate.month == shiftDetails.shift.startDate.month &&
      startDate.year == shiftDetails.shift.startDate.year &&
      shiftDetails.shift.startDate.day > startDate.day)
      shiftDetails.shift.startDate.day - (startDate.day - 1)
    else 1
    val daysInMonth: Int =
      if (shiftDetails.shift.endDate.exists(ed => startDate.month == ed.month && startDate.year == ed.year && ed.day >= startDate.day))
        shiftDetails.shift.endDate.map(_.day).getOrElse(startDate.day) - (startDate.day - 1)
      else daysCount
    startDay to daysInMonth
  }

  def staffTableEntriesForShift(shiftPeriod: ShiftPeriod, shiftDetails: ShiftDetails): Seq[StaffTableEntry] = {
    val dayAssignments = shiftDetails.shiftAssignments.assignments
      .filter(assignment => assignment.start >= shiftPeriod.start.millisSinceEpoch && assignment.end <= shiftPeriod.end.millisSinceEpoch)

    Iterator.iterate(shiftPeriod.start) { slotTime =>
      nextSlotTime(shiftPeriod, slotTime)
    }.takeWhile(_ < shiftPeriod.end).toSeq.zipWithIndex.map { case (currentTime, index) =>
      val nextTime = nextSlotTime(shiftPeriod, currentTime)
      findAndCreateDayTableAssignment(shiftPeriod, shiftDetails.terminal, shiftDetails.shift, dayAssignments, currentTime, index, nextTime)
    }
  }

  private def nextSlotTime(shiftPeriod: ShiftPeriod, slotTime: SDateLike): SDateLike = {
    //if hours is starting or ending in :30 then add 30 minutes interval instead of 60 mins
    if ((slotTime.getMinutes == 30 && shiftPeriod.intervalMinutes == 60) ||
      (slotTime.getHours == shiftPeriod.endHour && shiftPeriod.intervalMinutes == 60 && shiftPeriod.endMinute == 30)) {
      slotTime.addMinutes(30)
    } else {
      slotTime.addMinutes(shiftPeriod.intervalMinutes)
    }
  }

  private def findAndCreateDayTableAssignment(shiftPeriod: ShiftPeriod,
                                              terminal: Terminal,
                                              shift: Shift,
                                              dayAssignments: Seq[StaffAssignmentLike],
                                              currentTime: SDateLike,
                                              index: Int,
                                              nextTime: SDateLike): StaffTableEntry = {
    val maybeAssignment = dayAssignments.find(assignment => assignment.start == currentTime.millisSinceEpoch && assignment.terminal == terminal)

    val staff = maybeAssignment match {
      case Some(assignment) => assignment.numberOfStaff
      case None => if (shiftPeriod.firstDayEndsAfterMidnight) 0 else shift.staffNumber
    }

    StaffTableEntry(
      column = shiftPeriod.day,
      row = if (shiftPeriod.endsAfterMidnight) index + shiftPeriod.addToIndex else index,
      name = maybeAssignment.map(_.name).getOrElse(shift.shiftName),
      staffNumber = staff,
      startTime = ShiftDateTime(currentTime.getFullYear, currentTime.getMonth, currentTime.getDate, currentTime.getHours, currentTime.getMinutes),
      endTime = ShiftDateTime(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
    )
  }

  def generateShiftSummaries(viewingDate: SDateLike,
                             viewPeriod: String,
                             terminal: Terminal,
                             shifts: Seq[Shift],
                             shiftAssignments: ShiftAssignments,
                             intervalMinutes: Int,
                            ): Seq[ShiftSummaryStaffing] = {

    shifts.sortBy(_.startTime).zipWithIndex.map { case (shift, index) =>
      val tableEntries = createStaffTableEntries(
        firstDayByViewPeriod(viewPeriod, viewingDate),
        daysCountByViewPeriod(viewPeriod, viewingDate),
        intervalMinutes,
        ShiftDetails(shift, terminal, shiftAssignments),
      )
      val startDate = ShiftDate(day = shift.startDate.day, month = shift.startDate.month, year = shift.startDate.year)
      val maybeEndDate = shift.endDate.map(d => ShiftDate(day = d.day, month = d.month, year = d.year))
      val summary = ShiftSummary(shift.shiftName, shift.staffNumber, shift.startTime, shift.endTime, startDate, maybeEndDate)

      ShiftSummaryStaffing(index, summary, tableEntries)
    }
  }

  def updateChangeAssignment(previousChange: Seq[StaffTableEntry], newChange: Seq[StaffTableEntry]): Seq[StaffTableEntry] = {
    val previousChangeMap = previousChange.map(a => ShiftDateTime.toString(a.startTime) -> a).toMap
    val newChangeMap = newChange.map(a => ShiftDateTime.toString(a.startTime) -> a).toMap
    val mergedMap = previousChangeMap ++ newChangeMap
    mergedMap.values.toSeq
  }

  def updateAssignments(shifts: Seq[ShiftSummaryStaffing], changedAssignments: Seq[StaffTableEntry], slotMinutes: Int): Seq[ShiftSummaryStaffing] = {
    val changedAssignmentsWithSlotMap: Seq[StaffTableEntry] = changedAssignments.flatMap(a => StaffTableEntry.splitIntoSlots(a, slotMinutes))
    val changedAssignmentsMap: Map[String, StaffTableEntry] = changedAssignmentsWithSlotMap.map(a => ShiftDateTime.toString(a.startTime) -> a).toMap
    shifts.map { shift: ShiftSummaryStaffing =>
      val updatedAssignments = shift.staffTableEntries.map { assignment =>
        changedAssignmentsMap.getOrElse(ShiftDateTime.toString(assignment.startTime), assignment)
      }
      ShiftSummaryStaffing(shift.index, shift.shiftSummary, updatedAssignments.toSeq)
    }
  }
}
