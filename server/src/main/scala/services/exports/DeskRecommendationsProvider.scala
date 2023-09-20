package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.CrunchMinute
import services.LocalDateStream
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}



object DeskRecommendationsExport {
  private val relevantMinute: (LocalDate, Seq[CrunchMinute]) => Seq[CrunchMinute] =
    (current, minutes) => minutes.filter { m => SDate(m.minute).toLocalDate == current }

  def queuesProvider(utcQueuesProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed],
                    ): (LocalDate, LocalDate, Terminal) => Source[(LocalDate, Seq[CrunchMinute]), NotUsed] =
    LocalDateStream(utcQueuesProvider, startBufferDays = 0, endBufferDays = 0, transformData = relevantMinute)
}
