package drt.client.util

import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.time.{LocalDate, UtcDate}

object DateRange {
  def apply(start: LocalDate, end: LocalDate): Seq[LocalDate] =
    LazyList
      .iterate(SDate(start))(_.addDays(1))
      .takeWhile(_.toLocalDate <= end)
      .map(_.toLocalDate)

  def apply(start: UtcDate, end: UtcDate): Seq[UtcDate] =
    LazyList
      .iterate(SDate(start))(_.addDays(1))
      .takeWhile(_.toUtcDate <= end)
      .map(_.toUtcDate)
}
