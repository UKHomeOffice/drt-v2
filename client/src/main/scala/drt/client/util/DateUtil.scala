package drt.client.util

import uk.gov.homeoffice.drt.time.LocalDate

object DateUtil {
  def isNotValidDate(dateString: String): Boolean = {
    val regexYearPattern = "^[0-9]{4}$".r
    LocalDate.parse(dateString) match {
      case Some(d) =>
        regexYearPattern.findFirstMatchIn(d.year.toString).isEmpty
      case _ => true
    }
  }
}
