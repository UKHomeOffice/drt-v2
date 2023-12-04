package services

import akka.NotUsed
import akka.stream.scaladsl.Source
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

object LocalDateStream {
  def apply[A, B](utcDateRangeTerminalStream: (UtcDate, UtcDate) => Source[(UtcDate, Seq[A]), NotUsed],
                  startBufferDays: Int,
                  endBufferDays: Int,
                  transformData: (LocalDate, Seq[A]) => B,
                 ): (LocalDate, LocalDate) => Source[(LocalDate, B), NotUsed] =
    (start, end) => {
      val (utcStartDate: UtcDate, utcEndDate: UtcDate) = utcStartAndEnd(startBufferDays, endBufferDays, start, end)
      utcDateRangeTerminalStream(utcStartDate, utcEndDate)
        .sliding(3, 1)
        .filter(_.nonEmpty)
        .map { days =>
          val sortedDates = days.map(_._1).sorted
          val utcDate =
            if (days.size > 2) sortedDates.drop(1).head
            else sortedDates.head

          val localDate = LocalDate(utcDate.year, utcDate.month, utcDate.day)
          val value: Seq[A] = days.flatMap(_._2)

          (localDate, value)
        }
        .filter { case (localDate, _) => start <= localDate && localDate <= end }
        .map { case (localDate, data) => (localDate, transformData(localDate, data)) }
    }

  def utcStartAndEnd(startBufferDays: Int, endBufferDays: Int, start: LocalDate, end: LocalDate): (UtcDate, UtcDate) = {
    val localStartMinute = SDate(start)
    val utcStartDate = localStartMinute.addDays(-1 * startBufferDays).toUtcDate
    val utcEndDate = SDate(end).addDays(1 + endBufferDays).addMinutes(-1).toUtcDate
    (utcStartDate, utcEndDate)
  }
}
