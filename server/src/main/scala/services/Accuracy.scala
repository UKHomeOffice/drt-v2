package services

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

case class Accuracy(forecast: (LocalDate, SDateLike) => Future[Map[Terminal, Double]],
                    actual: LocalDate => Future[Map[Terminal, Double]],
                   )
                   (implicit ec: ExecutionContext) {
  def accuracy(date: LocalDate, forecastAt: SDateLike): Future[Map[Terminal, Double]] = {
    forecast(date, forecastAt).flatMap { terminalForecasts =>
      actual(date).map { terminalActuals =>
        terminalActuals.map {
          case (terminal, actual) => terminal -> accuracyPercentage(terminalForecasts.getOrElse(terminal, 0), actual)
        }
      }
    }
  }

  def accuracyPercentage(forecast: Double, actual: Double): Double = {
    if (actual == 0 && forecast != 0)
      0
    else
      (1 - (Math.abs(forecast - actual) / actual)) * 100
  }
}
