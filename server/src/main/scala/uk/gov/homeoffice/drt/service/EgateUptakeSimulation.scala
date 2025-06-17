package uk.gov.homeoffice.drt.service

import org.apache.pekko.http.impl.engine.rendering.RenderSupport.ChunkTransformer.flow.mapAsync
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.models.ManifestLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{DateRange, LocalDate, SDate, UtcDate}

import scala.:+
import scala.concurrent.{ExecutionContext, Future}

object EgateUptakeSimulation {
  def apply(flightsForDateAndTerminal: (UtcDate, Terminal) => Future[Seq[ApiFlightWithSplits]],
            manifestForFlight: UniqueArrival => Future[Option[ManifestLike]],
            egatePercentageForFlight: (ApiFlightWithSplits, Option[ManifestLike]) => Double,
            bxEgatePercentageForDateAndTerminal: (UtcDate, Terminal) => Future[Double],
            terminalsForDate: LocalDate => Seq[Terminal],
           )
           (implicit ec: ExecutionContext, mat: Materializer): (UtcDate, UtcDate) => Future[Seq[(UtcDate, Terminal, Double, Double)]] =
    (start, end) => {
      val dates = DateRange(start, end)
      val eventualDailyStats = Source(dates)
        .flatMap(d => Source(terminalsForDate(SDate(d).toLocalDate).map(t => (d, t))))
        .mapAsync(1) { case (date, terminal) =>
          flightsForDateAndTerminal(date, terminal).map(flights => (date, terminal, flights))
        }
        .mapAsync(1) {
          case (date, terminal, flights) =>
            Source(flights)
              .mapAsync(1)(flight => manifestForFlight(flight.unique).map(manifest => (flight, manifest)))
              .runWith(Sink.seq)
              .map { flightsWithMaybeManifests =>
                (date, terminal, flightsWithMaybeManifests)
              }
        }
        // get the bx egate percentage for the date and terminal
        .mapAsync(1) {
          case (date, terminal, flightsWithMaybeManifests) =>
            bxEgatePercentageForDateAndTerminal(date, terminal).map(bxEgatePercentage => (date, terminal, flightsWithMaybeManifests, bxEgatePercentage))
        }
        .mapAsync(1) { case (date, terminal, flightsWithMaybeManifests, bxEgatePercentage) =>
          
        }
      eventualDailyStats.runWith(Sink.seq)
    }
}
