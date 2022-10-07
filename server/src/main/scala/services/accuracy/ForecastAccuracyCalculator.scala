package services.accuracy

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.api.ForecastAccuracy
import services.{AccuracyForDate, SDate}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}

object ForecastAccuracyCalculator {
  def apply(dateToCalculate: LocalDate,
            daysToCalculate: List[Int],
            actualPaxNos: LocalDate => Future[Map[Terminal, Double]],
            forecastPaxNos: (LocalDate, SDateLike) => Future[Map[Terminal, Double]],
            today: LocalDate)
           (implicit ec: ExecutionContext, mat: Materializer): Future[ForecastAccuracy] = {
    if (SDate(today) > SDate(dateToCalculate)) {
      actualPaxNos(dateToCalculate).flatMap { actuals =>
        Source(daysToCalculate)
          .mapAsync(1) { daysAgo =>
            AccuracyForDate(dateToCalculate, forecastPaxNos, actuals, today).accuracy(dateToCalculate, daysAgo) match {
              case Some(eventualAccuracies) => eventualAccuracies.map(terminalAccs => (daysAgo, terminalAccs))
              case None => Future.successful((daysAgo, Map[Terminal, Option[Double]]()))
            }
          }
          .mapConcat {
            case (daysAgo, terminalAccuracies) =>
              terminalAccuracies.map {
                case (terminal, accuracy) =>
                  (daysAgo, terminal, accuracy)
              }
          }
          .runWith(Sink.seq)
          .map { accuracies =>
            val accuraciesByTerminal = accuracies
              .groupBy {
                case (_, terminal, _) => terminal
              }
              .mapValues(dta => SortedMap[Int, Option[Double]]() ++ dta.map {
                case (daysAgo, _, accuracy) => (daysAgo, accuracy)
              })
            ForecastAccuracy(dateToCalculate, accuraciesByTerminal)
          }
      }
    } else Future.successful(ForecastAccuracy(dateToCalculate, Map.empty))
  }
}
