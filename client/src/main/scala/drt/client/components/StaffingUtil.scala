package drt.client.components
import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.time.SDateLike

object StaffingUtil {

  def consecutiveDayForWeek(viewingDate: SDateLike): Seq[(SDateLike, String)] = {
    val firstDayOfMonth = SDate.firstDayOfMonth(viewingDate)
    val lastDayOfMonth = SDate.lastDayOfMonth(viewingDate)

    val startOfWeek = SDate.firstDayOfWeek(viewingDate)
    val endOfWeek = SDate.lastDayOfWeek(viewingDate)

    val adjustedStartOfWeek: SDateLike = if (startOfWeek.millisSinceEpoch < firstDayOfMonth.millisSinceEpoch) firstDayOfMonth else startOfWeek
    val adjustedEndOfWeek = if (endOfWeek.millisSinceEpoch > lastDayOfMonth.millisSinceEpoch) lastDayOfMonth else endOfWeek

    val days = (adjustedEndOfWeek.getDate - adjustedStartOfWeek.getDate) + 1

    daysOfWeek(adjustedStartOfWeek, days)
  }

  def consecutiveDay(viewingDate: SDateLike): Seq[(SDateLike, String)] = {
    daysOfWeek(viewingDate, 1)
  }

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
    daysOfWeek(adjustedStartDay, days)
  }

  def daysOfWeek(startDate: SDateLike, numberOfDays: Int): Seq[(SDateLike, String)] = {
    List.tabulate(numberOfDays)(i => {
      val date = startDate.addDays(i)
      val dayOfWeek = date.getDayOfWeek match {
        case 1 => "Monday"
        case 2 => "Tuesday"
        case 3 => "Wednesday"
        case 4 => "Thursday"
        case 5 => "Friday"
        case 6 => "Saturday"
        case 7 => "Sunday"
        case _ => "Unknown"
      }
      (date, dayOfWeek)
    })
  }
}
