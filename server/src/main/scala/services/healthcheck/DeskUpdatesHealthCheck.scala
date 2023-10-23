package services.healthcheck

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.CrunchMinute
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
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

          val lastUpdatedValues = relevantFlights
            .filter(_.lastUpdated.exists(_ < tenMinutesAgo.millisSinceEpoch))
            .map(_.lastUpdated)
          if (lastUpdatedValues.nonEmpty)
            lastUpdatedValues.maxBy(_.getOrElse(0L))
              .map { update =>
                crunchMinutes(date, date).map {
                  case (_, cms) => cms.exists(_.lastUpdated.exists(_ > update))
                }
              }
          else None
      }
      .collect {
        case Some(deskUpdatesSinceFlightUpdates) => deskUpdatesSinceFlightUpdates
      }
      .flatMapConcat(identity)
      .runWith(Sink.seq)
      .map(_.headOption)
  }
}
