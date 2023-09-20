package services

import akka.NotUsed
import akka.stream.scaladsl.Source
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

object LocalDateStream {
  def apply[A, B](utcDateRangeTerminalStream: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[A]), NotUsed],
                  startBufferDays: Int,
                  endBufferDays: Int,
                  transformData: (LocalDate, Seq[A]) => B,
                 ): (LocalDate, LocalDate, Terminal) => Source[(LocalDate, B), NotUsed] =
    (start, end, terminal) => {
      val localStartMinute = SDate(start)
      val utcStartDate = localStartMinute.addDays(-1 * startBufferDays).toUtcDate
      val utcEndDate = SDate(end).addDays(endBufferDays).toUtcDate
      utcDateRangeTerminalStream(utcStartDate, utcEndDate, terminal)
        .sliding(3, 1)
        .map { days =>
          val utcDate = days.map(_._1).sorted.drop(1).head
          val localDate = LocalDate(utcDate.year, utcDate.month, utcDate.day)
          (localDate, days.flatMap(_._2))
        }
        .filter { case (localDate, _) => start <= localDate && localDate <= end }
        .map { case (localDate, data) => (localDate, transformData(localDate, data)) }
    }
}
