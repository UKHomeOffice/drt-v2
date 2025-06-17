package uk.gov.homeoffice.drt.service

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.models.ManifestLike
import uk.gov.homeoffice.drt.ports.Queues.{EGate, Queue}
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
      //        .mapAsync(1) { case (date, terminal, flightsWithMaybeManifests, bxEgatePercentage) =>
      //
      //        }
      eventualDailyStats.runWith(Sink.seq)
    }

  def bxVersusDrtEgatePercentageForDate(bxEgatePercentageForDateAndTerminal: (UtcDate, Terminal) => Future[Double],
                                        drtEgatePercentageForDateAndTerminal: (UtcDate, Terminal) => Future[Double],
                                       )
                                       (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Double, Double)] =
    (date, terminal) => {
      for {
        bxEgatePercentage <- bxEgatePercentageForDateAndTerminal(date, terminal)
        drtEgatePercentage <- drtEgatePercentageForDateAndTerminal(date, terminal)
      } yield (bxEgatePercentage, drtEgatePercentage)
    }

  def drtEgatePercentageForDateAndTerminal(flightsWithManifestsForDateAndTerminal: (UtcDate, Terminal) => Future[Seq[(ApiFlightWithSplits, Option[ManifestLike])]],
                                           egateAndDeskPaxForFlight: (ApiFlightWithSplits, Option[ManifestLike], Double) => (Int, Int),
                                          )
                                          (implicit ec: ExecutionContext): (UtcDate, Terminal, Double) => Future[Double] =
    (date, terminal, egateUptake) => {
      flightsWithManifestsForDateAndTerminal(date, terminal)
        .map { flightsWithManifests =>
          val (egatePax, deskPax) = flightsWithManifests.map { case (flight, manifest) =>
            egateAndDeskPaxForFlight(flight, manifest, egateUptake)
          }.foldLeft((0, 0)) { case ((egateAcc, deskAcc), (egate, desk)) => (egateAcc + egate, deskAcc + desk) }
          if (egatePax + deskPax > 0) {
            (egatePax.toDouble / (egatePax + deskPax)) * 100.0
          } else {
            0.0
          }
        }
    }

  def bxEgatePercentageForDateAndTerminal(bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]],
                                         )
                                         (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[Double] =
    (date, terminal) => {
      bxQueueTotalsForPortAndDate(date, terminal)
        .map { queueTotals =>
          val egatePax = queueTotals.getOrElse(EGate, 0)
          val totalPax = queueTotals.values.sum
          if (totalPax > 0) {
            (egatePax.toDouble / totalPax) * 100.0
          } else {
            0.0
          }
        }
    }
}
