package controllers.application

import drt.shared.{Shift, ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

case class ShiftDetails(shift: Shift,
                        terminal: Terminal,
                        shiftAssignments: ShiftAssignments)

case class ShiftDate(year: Int, month: Int, day: Int, hour: Int, minute: Int) {
  def isEqual(shiftDate: ShiftDate): Boolean = {
    year == shiftDate.year &&
      month == shiftDate.month &&
      day == shiftDate.day &&
      hour == shiftDate.hour &&
      minute == shiftDate.minute
  }
}

case class StaffTableEntry(
                            column: Int,
                            row: Int,
                            name: String,
                            staffNumber: Int,
                            startTime: ShiftDate,
                            endTime: ShiftDate
                          )

case class ShiftSummary(
                         name: String,
                         defaultStaffNumber: Int,
                         startTime: String,
                         endTime: String
                       )

case class ShiftSummaryStaffing(
                                 index: Int,
                                 shiftSummary: ShiftSummary,
                                 staffTableEntries: Seq[StaffTableEntry]
                               )

case class ShiftPeriod(start: SDateLike,
                       end: SDateLike,
                       endHour: Int,
                       endMinute: Int,
                       interval: Int,
                       day: Int,
                       isShiftEndAfterMidnight: Boolean,
                       isFirstDayForShiftEndAfterMidnight: Boolean,
                       addToIndex: Int)

object ShiftsHelpers {

  val numberOfDaysInMonth: SDateLike => Int = { viewingDate: SDateLike =>
    def daysInMonth(year: Int, month: Int): Int =
      java.time.LocalDate.of(year, month, 1).lengthOfMonth()

    daysInMonth(viewingDate.getFullYear, viewingDate.getMonth)
  }
  val daysCountByDayRange: (String, SDateLike) => Int = { (dayRange, viewingDate) =>
    dayRange match {
      case "weekly" => 7
      case "daily" => 1
      case _ => numberOfDaysInMonth(viewingDate)
    }
  }

  private def firstDayOfWeek(date: SDateLike): SDateLike = {
    val dayOfWeek = date.getDayOfWeek
    val daysToSubtract = if (dayOfWeek == 1) 0 else dayOfWeek - 1
    date.addDays(-daysToSubtract)
  }

  private def firstDayOfMonth(today: SDateLike): SDateLike = SDate(y = today.getFullYear, m = today.getMonth, d = 1)

  val firstDayByDayRange: (String, SDateLike) => SDateLike = { (dayRange, viewingDate) =>
    dayRange match {
      case "weekly" =>
        firstDayOfWeek(viewingDate)
      case "daily" => viewingDate
      case _ => firstDayOfMonth(viewingDate)
    }
  }

  private def isStartOrEndTimeFinishAtThirtyMinutesPastAndHour(shiftPeriod: ShiftPeriod, intervalTime: SDateLike) = {
    //if hours is starting or ending in :30 then add 30 minutes interval instead of 60 mins
    if ((intervalTime.getMinutes == 30 && shiftPeriod.interval == 60) ||
      (intervalTime.getHours == shiftPeriod.endHour && shiftPeriod.interval == 60 && shiftPeriod.endMinute == 30)) {
      intervalTime.addMinutes(30)
    } else {
      intervalTime.addMinutes(shiftPeriod.interval)
    }
  }

  private def findAndCreateDayTableAssignment(shiftPeriod: ShiftPeriod,
                                              terminal: Terminal,
                                              shift: Shift,
                                              dayAssignments: Seq[StaffAssignmentLike],
                                              currentTime: SDateLike,
                                              index: Int,
                                              nextTime: SDateLike) = {
    val foundAssignment = dayAssignments.find(assignment => assignment.start == currentTime.millisSinceEpoch && assignment.terminal == terminal)
    foundAssignment match {
      case Some(assignment) =>
        StaffTableEntry(
          column = shiftPeriod.day,
          row = if (shiftPeriod.isShiftEndAfterMidnight) index + shiftPeriod.addToIndex else index,
          name = assignment.name,
          staffNumber = assignment.numberOfStaff,
          startTime = ShiftDate(currentTime.getFullYear, currentTime.getMonth, currentTime.getDate, currentTime.getHours, currentTime.getMinutes),
          endTime = ShiftDate(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
        )
      case None =>
        StaffTableEntry(
          column = shiftPeriod.day,
          row = if (shiftPeriod.isShiftEndAfterMidnight) index + shiftPeriod.addToIndex else index,
          name = shift.shiftName,
          staffNumber = if (shiftPeriod.isFirstDayForShiftEndAfterMidnight) 0 else shift.staffNumber,
          startTime = ShiftDate(currentTime.getFullYear, currentTime.getMonth, currentTime.getDate, currentTime.getHours, currentTime.getMinutes),
          endTime = ShiftDate(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
        )
    }
  }

  def createStaffTableEntries(startDate: SDateLike,
                              daysCount: Int,
                              interval: Int,
                              shiftDetails: ShiftDetails
                             ): Seq[StaffTableEntry] = {

    val Array(shiftStartHour, shiftStartMinute) = shiftDetails.shift.startTime.split(":").map(_.toInt)
    val Array(shiftEndHour, shiftEndMinute) = shiftDetails.shift.endTime.split(":").map(_.toInt)
    var currentDay = startDate

    val isShiftEndAfterMidnight = shiftEndHour < shiftStartHour || (shiftEndHour == shiftStartHour && shiftEndMinute < shiftStartMinute)
    //For all the days in the period, create the staff table entries for the shift
    (1 to daysCount).flatMap { day =>
      val shiftStartTime = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, shiftStartHour, shiftStartMinute)
      val shiftEndTime = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, shiftEndHour, shiftEndMinute)
      val midnightNextDay = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, 0, 0).addDays(1)
      val beforeMidnightPeriod = ShiftPeriod(
        start = shiftStartTime,
        end = if (isShiftEndAfterMidnight) midnightNextDay else shiftEndTime,
        endHour = shiftEndHour,
        endMinute = shiftEndMinute,
        interval = interval,
        day = day,
        isShiftEndAfterMidnight = isShiftEndAfterMidnight,
        isFirstDayForShiftEndAfterMidnight = false,
        addToIndex = 0
      )


      val beforeMidnightEntries = staffTableEntriesForShift(beforeMidnightPeriod, shiftDetails)

      val isFirstNightShiftForMonth = day == 1 && isShiftEndAfterMidnight && daysCount > 7
      val fromMidNightDateStartTime = if (isShiftEndAfterMidnight) {
        val midnightStart = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, 0, 0)
        val endTime = SDate(currentDay.getFullYear, currentDay.getMonth, currentDay.getDate, shiftEndHour, shiftEndMinute)

        val firstDayMidnightToStartTimePeriod = beforeMidnightPeriod.copy(
          start = midnightStart,
          end = endTime,
          isFirstDayForShiftEndAfterMidnight = isFirstNightShiftForMonth,
          addToIndex = beforeMidnightEntries.size
        )

        staffTableEntriesForShift(firstDayMidnightToStartTimePeriod, shiftDetails)
      } else Seq.empty

      currentDay = currentDay.addDays(1)

      beforeMidnightEntries ++ fromMidNightDateStartTime
    }
  }

  def staffTableEntriesForShift(shiftPeriod: ShiftPeriod, shiftDetails: ShiftDetails): Seq[StaffTableEntry] = {
    val dayAssignments = shiftDetails.shiftAssignments.assignments
      .filter(assignment => assignment.start >= shiftPeriod.start.millisSinceEpoch && assignment.end <= shiftPeriod.end.millisSinceEpoch)
    Iterator.iterate(shiftPeriod.start) { intervalTime =>
      isStartOrEndTimeFinishAtThirtyMinutesPastAndHour(shiftPeriod, intervalTime)
    }.takeWhile(_ < shiftPeriod.end).toSeq.zipWithIndex.map { case (currentTime, index) =>
      val nextTime = isStartOrEndTimeFinishAtThirtyMinutesPastAndHour(shiftPeriod, currentTime)
      findAndCreateDayTableAssignment(shiftPeriod, shiftDetails.terminal, shiftDetails.shift, dayAssignments, currentTime, index, nextTime)
    }
  }

  def generateShiftSummaries(viewingDate: SDateLike,
                             dayRange: String,
                             terminal: Terminal,
                             shifts: Seq[Shift],
                             shiftAssignments: ShiftAssignments,
                             interval: Int): Seq[ShiftSummaryStaffing] = {
    shifts.sortBy(_.startTime).zipWithIndex.map { case (s, index) =>
      ShiftSummaryStaffing(
        index = index,
        shiftSummary = ShiftSummary(s.shiftName, s.staffNumber, s.startTime, s.endTime),
        staffTableEntries = createStaffTableEntries(firstDayByDayRange(dayRange, viewingDate),
          daysCountByDayRange(dayRange, viewingDate),
          interval,
          ShiftDetails(s, terminal, shiftAssignments))
      )
    }
  }
}
