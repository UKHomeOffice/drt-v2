package drt.client.util

import uk.gov.homeoffice.drt.time.LocalDate
import drt.client.components.DaySelectorComponent.searchForm
import uk.gov.homeoffice.drt.time.SDateLike
import drt.client.services.JSDateConversions.SDate
import drt.client.SPAMain.TerminalPageTabLoc

object DateUtil {
  def isNotValidDate(dateString: String): Boolean = {
    val regexYearPattern = "^[0-9]{4}$".r
    LocalDate.parse(dateString) match {
      case Some(d) =>
        regexYearPattern.findFirstMatchIn(d.year.toString).isEmpty
      case _ => true
    }
  }

  def displayArrivalSearchDate(selectedDate: SDateLike, terminalPageTab: TerminalPageTabLoc): String = {
      val searchFormForDate = searchForm(selectedDate, terminalPageTab)

      val fromSDate = SDate(searchFormForDate.fromTime.valueOf().toLong)
      val toSDate = SDate(searchFormForDate.toTime.valueOf().toLong)
      val from = fromSDate.toHoursAndMinutes
      val to = toSDate.toHoursAndMinutes
      if (fromSDate.toLocalDate != toSDate.toLocalDate)
        s"${searchFormForDate.displayText} ($from ${fromSDate.toLocalDate.ddmmyyyy} to $to ${toSDate.toLocalDate.ddmmyyyy})"
      else
        s"${searchFormForDate.displayText} ($from to $to)"
    }
}
