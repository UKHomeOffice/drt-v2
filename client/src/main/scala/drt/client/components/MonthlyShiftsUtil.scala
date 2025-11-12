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
                              recommendedStaff: Map[Long, Int],
                             ): Seq[StaffTableEntry] = {

    val dayRange = dayRangeForView(startDate, daysCount, shiftDetails.shift)

    val Array(shiftStartHour, shiftStartMinute) = shiftDetails.shift.startTime.split(":").map(_.toInt)
    val Array(shiftEndHour, shiftEndMinute) = shiftDetails.shift.endTime.split(":").map(_.toInt)

    val shiftEndsAfterMidnight = shiftEndHour < shiftStartHour || (shiftEndHour == shiftStartHour && shiftEndMinute < shiftStartMinute)
    //For all the days in the period, create the staff table entries for the shift
    (dayRange.start to dayRange.end).flatMap { day =>
      val currentDay = SDate(startDate).addDays(day - 1).toLocalDate
      val shiftStartTime = SDate(currentDay).addHours(shiftStartHour).addMinutes(shiftStartMinute)
      val shiftEndTime = SDate(currentDay).addHours(shiftEndHour).addMinutes(shiftEndMinute)
      val midnightNextDay = SDate(currentDay).addDays(1)

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

      val entriesBeforeMidnight = staffTableEntriesForShift(beforeMidnightPeriod, shiftDetails, recommendedStaff)

      val isFirstNightShiftForMonth = day == 1 && shiftEndsAfterMidnight && daysCount > 7

      val entriesFromMidnight =
        if (shiftEndsAfterMidnight) {
          val midnightStart = SDate(currentDay)

          val periodFromMidnight = beforeMidnightPeriod.copy(
            start = midnightStart,
            end = shiftEndTime,
            firstDayEndsAfterMidnight = isFirstNightShiftForMonth,
            addToIndex = entriesBeforeMidnight.size
          )

          staffTableEntriesForShift(periodFromMidnight, shiftDetails, recommendedStaff)
        }
        else Seq.empty

      entriesBeforeMidnight ++ entriesFromMidnight
    }
  }

  private def dayRangeForView(startDate: LocalDate, daysCount: Int, shift: Shift): Range.Inclusive = {
    val startDateBeforeShiftStartDate = startDate.month == shift.startDate.month &&
      startDate.year == shift.startDate.year &&
      startDate.day < shift.startDate.day

    val startDay: Int = if (startDateBeforeShiftStartDate) shift.startDate.day - (startDate.day - 1) else 1

    val daysInMonth: Int =
      if (shift.endDate.exists(ed => startDate.month == ed.month && startDate.year == ed.year && ed.day >= startDate.day))
        shift.endDate.map(_.day).getOrElse(startDate.day) - (startDate.day - 1)
      else daysCount

    startDay to daysInMonth
  }

  def staffTableEntriesForShift(shiftPeriod: ShiftPeriod, shiftDetails: ShiftDetails, recommendedStaff: Map[Long, Int]): Seq[StaffTableEntry] = {
    val dayAssignments = shiftDetails.shiftAssignments.assignments
      .filter(assignment => assignment.start >= shiftPeriod.start.millisSinceEpoch && assignment.end <= shiftPeriod.end.millisSinceEpoch)
      .map { a =>
        ((a.start, a.terminal), a)
      }
      .toMap

    Iterator
      .iterate(shiftPeriod.start)(slotTime => nextSlotTime(shiftPeriod, slotTime))
      .takeWhile(_ < shiftPeriod.end).toSeq.zipWithIndex.map { case (slotStart, index) =>
        val nextTime = nextSlotTime(shiftPeriod, slotStart)
        val maybeAssignment = dayAssignments.get((slotStart.millisSinceEpoch, shiftDetails.terminal))
        val staffRec = recommendedStaff.getOrElse(slotStart.millisSinceEpoch, 0)
        findAndCreateDayTableAssignment(shiftPeriod, shiftDetails.shift, staffRec, maybeAssignment, slotStart, index, nextTime)
      }
  }

  private def nextSlotTime(shiftPeriod: ShiftPeriod, slotTime: SDateLike): SDateLike =
    //if slot time starts or ends at 30 minutes then override the shift's interval minutes with 30 minutes
    if ((slotTime.getMinutes == 30 && shiftPeriod.intervalMinutes == 60) ||
      (slotTime.getHours == shiftPeriod.endHour && shiftPeriod.intervalMinutes == 60 && shiftPeriod.endMinute == 30)) {
      slotTime.addMinutes(30)
    } else {
      slotTime.addMinutes(shiftPeriod.intervalMinutes)
    }

  private def findAndCreateDayTableAssignment(shiftPeriod: ShiftPeriod,
                                              shift: Shift,
                                              staffRecommendation: Int,
                                              maybeAssignment: Option[StaffAssignmentLike],
                                              slotStart: SDateLike,
                                              index: Int,
                                              nextTime: SDateLike): StaffTableEntry = {
    val staff = maybeAssignment match {
      case Some(assignment) => assignment.numberOfStaff
      case None => if (shiftPeriod.firstDayEndsAfterMidnight) 0 else shift.staffNumber
    }

    StaffTableEntry(
      column = shiftPeriod.day,
      row = if (shiftPeriod.endsAfterMidnight) index + shiftPeriod.addToIndex else index,
      name = maybeAssignment.map(_.name).getOrElse(shift.shiftName),
      staffRecommendation = staffRecommendation,
      staffNumber = staff,
      startTimeMillis = slotStart.millisSinceEpoch,
      startTime = ShiftDateTime(slotStart.getFullYear, slotStart.getMonth, slotStart.getDate, slotStart.getHours, slotStart.getMinutes),
      endTime = ShiftDateTime(nextTime.getFullYear, nextTime.getMonth, nextTime.getDate, nextTime.getHours, nextTime.getMinutes)
    )
  }

  def generateShiftSummaries(viewingDate: SDateLike,
                             viewPeriod: String,
                             terminal: Terminal,
                             shifts: Seq[Shift],
                             shiftAssignments: ShiftAssignments,
                             recommendedStaff: Map[Long, Int],
                             intervalMinutes: Int,
                            ): Seq[ShiftSummaryStaffing] = {

    shifts.sortBy(_.startTime).zipWithIndex.map { case (shift, index) =>
      val tableEntries = createStaffTableEntries(
        firstDayByViewPeriod(viewPeriod, viewingDate),
        daysCountByViewPeriod(viewPeriod, viewingDate),
        intervalMinutes,
        ShiftDetails(shift, terminal, shiftAssignments),
        recommendedStaff
      )
      val startDate = ShiftDate(day = shift.startDate.day, month = shift.startDate.month, year = shift.startDate.year)
      val maybeEndDate = shift.endDate.map(d => ShiftDate(day = d.day, month = d.month, year = d.year))
      val summary = ShiftSummary(shift.shiftName, shift.staffNumber, shift.startTime, shift.endTime, startDate, maybeEndDate)

      ShiftSummaryStaffing(index, summary, tableEntries)
    }
  }

  def updateTableEntries(existing: Seq[StaffTableEntry], updates: Seq[StaffTableEntry]): Seq[StaffTableEntry] = {
    val previousChangeMap = existing.map(a => ShiftDateTime.toString(a.startTime) -> a).toMap
    val newChangeMap = updates.map(a => ShiftDateTime.toString(a.startTime) -> a).toMap
    val mergedMap = previousChangeMap ++ newChangeMap
    mergedMap.values.toSeq
  }

  def updateShiftSummaries(shifts: Seq[ShiftSummaryStaffing], changedAssignments: Seq[StaffTableEntry], slotMinutes: Int): Seq[ShiftSummaryStaffing] = {
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
