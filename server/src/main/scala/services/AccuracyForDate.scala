package services

import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

case class AccuracyForDate(date: LocalDate,
                           forecast: (LocalDate, SDateLike) => Future[Map[Terminal, Double]],
                           terminalActuals: Map[Terminal, Double],
                           today: LocalDate
                          )
                          (implicit ec: ExecutionContext) {
  private val log = LoggerFactory.getLogger(getClass)

  def accuracy(date: LocalDate, daysBeforeDate: Int): Option[Future[Map[Terminal, Double]]] = {
    val atDate = SDate(date).addDays(-1 * daysBeforeDate)
    val dateIsHistoric = SDate(date).millisSinceEpoch <= SDate(today).millisSinceEpoch
    if (dateIsHistoric) {
      val eventualAccuracy = forecast(date, atDate).map { terminalForecasts =>
        terminalActuals.map {
          case (terminal, actual) => terminal -> accuracyPercentage(terminalForecasts.getOrElse(terminal, 0), actual)
        }
      }
      Option(eventualAccuracy)
    } else {
      log.warn(s"AccuracyForDate: accuracy: date $date is not in the past, so no accuracy")
      None
    }
  }

  def accuracyPercentage(forecast: Double, actual: Double): Double = {
    if (actual == 0 && forecast != 0)
      0
    else
      (forecast / actual) * 100
  }
}
