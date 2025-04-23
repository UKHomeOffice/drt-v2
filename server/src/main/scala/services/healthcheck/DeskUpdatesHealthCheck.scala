package services.healthcheck

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

case class DeskUpdatesHealthCheck(now: () => SDateLike,
                                  flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                  crunchMinutes: (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed]
                                 )
                                 (implicit ec: ExecutionContext, mat: Materializer) {
  private def dateToConsider = now().toUtcDate

  def healthy(): Future[Option[Boolean]] = {
    flights(dateToConsider, dateToConsider)
      .map {
        case (date, flights) =>
          val tenMinutesAgo = now().addMinutes(-10)
          val relevantFlights = flights.filterNot(_.apiFlight.Origin.isDomesticOrCta)

          val terminalLastUpdated = relevantFlights
            .filter(_.lastUpdated.exists(_ < tenMinutesAgo.millisSinceEpoch))
            .groupBy(_.apiFlight.Terminal)
            .view
            .mapValues(_.map(_.lastUpdated.getOrElse(0L)).max)
            .toSeq

          if (terminalLastUpdated.nonEmpty) {
            val desksHaveBeenUpdated = crunchMinutes(date, date).map {
              case (_, cms) =>
                terminalLastUpdated.exists { case (terminal, updated) =>
                  cms.filter(_.terminal == terminal)
                    .exists(_.lastUpdated.exists(_ > updated))
                }
            }
            Option(desksHaveBeenUpdated)
          } else None
      }
      .collect { case Some(g) => g}
      .flatMapConcat(identity)
      .runWith(Sink.seq)
      .map(_.headOption)
  }
}
