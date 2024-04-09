package services

import org.slf4j.LoggerFactory
import ForecastAccuracyComparison.{maybeAbsoluteError, maybeAverageFlightError}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

case class ErrorValues(predictionRmse: Option[Double], predictionError: Option[Double], legacyRmse: Option[Double], legacyError: Option[Double])

object ForecastAccuracyComparison {
  private val log = LoggerFactory.getLogger(getClass)

  def percentageForecastOfActuals(actuals: Set[UniqueArrival], forecasts: Set[UniqueArrival]): Double =
    actuals.count(a => forecasts.contains(a)).toDouble / actuals.size.toDouble

  def maybeAverageFlightError(actuals: Map[UniqueArrival, Int], forecasts: Map[UniqueArrival, Int], minCoverage: Double): Option[Double] = {
    val coverage = percentageForecastOfActuals(actuals.keySet, forecasts.keySet)
    if (coverage >= minCoverage) {
      val actualsSet = actuals.keySet

      val intersect = actualsSet.intersect(forecasts.keySet)

      val totalError = intersect
        .toList
        .filter(ua => actuals(ua) > 0)
        .map { ua =>
          Math.abs(forecasts(ua) - actuals(ua)).toDouble / actuals(ua)
        }
        .sum

      val averageError = (100 * (totalError / intersect.size)).round.toDouble / 100

      Option(averageError)
    } else {
      log.warn(f"coverage too low for comparison: ${coverage * 100}%.2f%% - ${actuals.size} actuals, ${forecasts.size} forecasts}")
      None
    }
  }

  def maybeAbsoluteError(actuals: Map[UniqueArrival, Int], forecasts: Map[UniqueArrival, Int], minCoverage: Double): Option[Double] = {
    val coverage = percentageForecastOfActuals(actuals.keySet, forecasts.keySet)
    if (coverage >= minCoverage) {
      val actualsSet = actuals.keySet

      val intersect = actualsSet.intersect(forecasts.keySet)

      val totalActual = intersect.toList.map(actuals(_)).sum
      val totalForecast = intersect.toList.map(forecasts(_)).sum
      val diff = totalForecast - totalActual

      val absoluteError = (100 * (diff.toDouble / totalActual)) / 100

      Option(absoluteError)
    } else None
  }

}

case class ForecastAccuracyComparison(forecast: (LocalDate, SDateLike) => Future[Map[Terminal, Seq[Arrival]]],
                                      terminalActuals: Map[Terminal, Seq[Arrival]],
                                      today: LocalDate
                                            )
                                     (implicit ec: ExecutionContext) {
  private val log = LoggerFactory.getLogger(getClass)

  def accuracy(date: LocalDate, daysBeforeDate: Int): Option[Future[Map[Terminal, ErrorValues]]] = {
    val atDate = SDate(date).addDays(-1 * daysBeforeDate)
    val dateIsHistoric = SDate(date).millisSinceEpoch <= SDate(today).millisSinceEpoch
    if (dateIsHistoric) {
      val eventualAccuracy = forecast(date, atDate).map { terminalForecasts =>
        terminalActuals.map {
          case (terminal, actualArrivals) =>
            val forecastArrivals = terminalForecasts.getOrElse(terminal, Seq())
            val actualPax = paxNosForFeeds(actualArrivals, List(LiveFeedSource, ApiFeedSource))
            val predictedPax = paxNosForFeeds(forecastArrivals, List(MlFeedSource))
            val legacyPax = paxNosForFeeds(forecastArrivals, List(ForecastFeedSource, HistoricApiFeedSource, AclFeedSource))
            val minimumPopulated = 0.75
            val errors = ErrorValues(
              predictionRmse = maybeAverageFlightError(actualPax.toMap, predictedPax.toMap, minimumPopulated),
              predictionError = maybeAbsoluteError(actualPax.toMap, predictedPax.toMap, minimumPopulated),
              legacyRmse = maybeAverageFlightError(actualPax.toMap, legacyPax.toMap, minimumPopulated),
              legacyError = maybeAbsoluteError(actualPax.toMap, legacyPax.toMap, minimumPopulated)
            )
            (terminal, errors)
        }
      }
      Option(eventualAccuracy)
    } else {
      log.warn(s"AccuracyForDate: accuracy: date $date is not in the past, so no accuracy")
      None
    }
  }

  private def paxNosForFeeds(arrivals: Seq[Arrival], feeds: List[FeedSource]): Seq[(UniqueArrival, Int)] =
    arrivals
      .map { a => (a.unique, a.bestPcpPaxEstimate(feeds)) }
      .collect { case (ua, Some(p)) => ua -> p }
}
