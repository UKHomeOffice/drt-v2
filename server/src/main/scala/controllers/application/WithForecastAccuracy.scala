package controllers.application

import akka.stream.scaladsl.{Sink, Source}
import controllers.Application
import drt.shared.api.ForecastAccuracy
import play.api.mvc.{Action, AnyContent}
import services.AccuracyForDate
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default.write

import scala.collection.immutable.SortedMap
import scala.concurrent.Future


trait WithForecastAccuracy {
  self: Application =>

  def getForecastAccuracy(dateStr: String): Action[AnyContent] = auth {
    Action.async { _ =>
      val maybeResponse = for {
        date <- LocalDate.parse(dateStr)
      } yield {
        val now = ctrl.now()
        ctrl.actualPaxNos(date).flatMap { actuals =>
          Source(List(1, 3, 7, 14, 30))
            .mapAsync(1) { daysAgo =>
              val dateAgo = now.addDays(-1 * daysAgo)
              val acc = AccuracyForDate(date, ctrl.forecastPaxNos, actuals, () => ctrl.now().toLocalDate)
              acc.accuracy(date, dateAgo) match {
                case Some(eventualAccuracies) => eventualAccuracies.map(terminalAccs => (daysAgo, terminalAccs))
                case None => Future.successful((daysAgo, Map[Terminal, Double]()))
              }
            }
            .mapConcat {
              case (daysAgo, terminalAccs) =>
                terminalAccs.map {
                  case (terminal, accuracy) =>
                    (daysAgo, terminal, accuracy)
                }
            }
            .runWith(Sink.seq)
            .map { accuracies =>
              val accsByTerminal: Map[Terminal, SortedMap[Int, Double]] = accuracies
                .groupBy {
                  case (_, terminal, _) => terminal
                }
                .mapValues(dta => SortedMap[Int, Double]() ++ dta.map {
                  case (daysAgo, _, accuracy) => (daysAgo, accuracy)
                })
              ForecastAccuracy(date, accsByTerminal)
            }
        }
      }
      maybeResponse match {
        case Some(eventualAccuracy) =>
          eventualAccuracy.map (acc => Ok(write(acc)))
        case None =>
          Future.successful(BadRequest("Invalid date"))
      }
    }
  }
}
