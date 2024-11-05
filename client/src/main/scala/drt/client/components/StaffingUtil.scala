package drt.client.components

import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.time.SDateLike

object StaffingUtil {
  def navigationDates(viewingDate: SDateLike, isWeekly: Boolean, isDaily: Boolean, now: () => SDateLike): (SDateLike, SDateLike) = {
    val currentMonthStart = SDate.firstDayOfMonth(now())
    val adjustedViewingDate = if (viewingDate.millisSinceEpoch < currentMonthStart.millisSinceEpoch) {
      currentMonthStart
    } else {
      viewingDate
    }

    if (isWeekly) {
      val previousWeekDate = {
        val firstDayOfWeek = SDate.firstDayOfWeek(viewingDate)
        val potentialPreviousWeekDate = if (firstDayOfWeek.millisSinceEpoch < currentMonthStart.millisSinceEpoch) {
          adjustedViewingDate
        } else adjustedViewingDate.addDays(-7)
        potentialPreviousWeekDate
      }
      val nextWeekDate = {
        val currentMonthStart = SDate.firstDayOfMonth(now())
        val lastDayOfFifthMonth = SDate.lastDayOfMonth(currentMonthStart.addMonths(5))
        val lastDayOfWeek = SDate.lastDayOfWeek(viewingDate)

        if (lastDayOfWeek.millisSinceEpoch > lastDayOfFifthMonth.millisSinceEpoch) {
          adjustedViewingDate
        } else {
          adjustedViewingDate.addDays(7)
        }
      }
      (previousWeekDate, nextWeekDate)
    } else if (isDaily) {
      val previousDayDate = adjustedViewingDate.addDays(-1)
      val nextDayDate = {
        val currentMonthStart = SDate.firstDayOfMonth(now())
        val lastDayOfFifthMonth = SDate.lastDayOfMonth(currentMonthStart.addMonths(5))
        if (adjustedViewingDate.millisSinceEpoch == lastDayOfFifthMonth.millisSinceEpoch) {
          adjustedViewingDate
        } else {
          adjustedViewingDate.addDays(1)
        }
      }
      val isFirstDayOfCurrentMonth = adjustedViewingDate.millisSinceEpoch == currentMonthStart.millisSinceEpoch
      if (isFirstDayOfCurrentMonth)
        (currentMonthStart, nextDayDate)
      else
        (previousDayDate, nextDayDate)
    } else {
      val previousMonthDate = adjustedViewingDate.addMonths(-1)
      val nextMonthDate = adjustedViewingDate.addMonths(1)
      val sixMonthsFromNow = currentMonthStart.addMonths(5)
      val finalNextMonthDate = if (nextMonthDate.millisSinceEpoch > sixMonthsFromNow.millisSinceEpoch)
        sixMonthsFromNow
      else
        nextMonthDate
      if (previousMonthDate.millisSinceEpoch < currentMonthStart.millisSinceEpoch) {
        (currentMonthStart, finalNextMonthDate)
      } else
        (previousMonthDate, finalNextMonthDate)
    }
  }

  def consecutiveDayForWeek(viewingDate: SDateLike): Seq[(SDateLike, String)] = {
    val startOfWeek = SDate.firstDayOfWeek(viewingDate)
    val endOfWeek = SDate.lastDayOfWeek(viewingDate)

    val days = (endOfWeek.millisSinceEpoch - startOfWeek.millisSinceEpoch) / (1000 * 60 * 60 * 24) + 1
    dateRangeDays(startOfWeek, days.toInt)
  }

//  def consecutiveDay(viewingDate: SDateLike): Seq[(SDateLike, String)] = {
//    daysOfWeek(viewingDate, 1)
//  }

  def consecutiveDaysInMonth(startDay: SDateLike, endDay: SDateLike): Seq[(SDateLike, String)] = {
    val lastDayOfPreviousMonth = SDate(startDay.getFullYear, startDay.getMonth, 1).addDays(-1)
    val adjustedStartDay: SDateLike = if (startDay.getDate == lastDayOfPreviousMonth.getDate) {
      if (startDay.getMonth == 11) // December
        SDate(startDay.getFullYear + 1, 0, 1) // January of the next year
      else
        SDate(startDay.getFullYear, startDay.getMonth + 1, 1) // Next month of the same year
    } else {
      startDay
    }

    val adjustedEndDay = if (startDay.getDate > endDay.getDate) {
      SDate(endDay.getFullYear, endDay.getMonth, 7)
    } else endDay

    val days = (adjustedEndDay.getDate - adjustedStartDay.getDate) + 1
    dateRangeDays(adjustedStartDay, days)
  }

  def dateRangeDays(startDate: SDateLike, numberOfDays: Int): Seq[(SDateLike, String)] = {
    List.tabulate(numberOfDays)(i => {
      val date = startDate.addDays(i)
      (date, date.getDayOfWeekString)
    })
  }
}
