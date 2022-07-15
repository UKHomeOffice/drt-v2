package controllers.application

import akka.stream.scaladsl.{Sink, Source}
import controllers.Application
import drt.shared.api.ForecastAccuracy
import play.api.mvc.{Action, AnyContent}
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
        Source(List(1, 3, 7, 14, 30))
          .mapAsync(1) { daysAgo =>
            ctrl.accuracy
              .accuracy(date, now.addDays(-1 * daysAgo))
              .map(terminalAccs => ((daysAgo, terminalAccs)))
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
            val accsByTerminal = accuracies
              .groupBy {
                case (_, terminal, _) => terminal
              }
              .mapValues(dta => SortedMap[Int, Double]() ++ dta.map {
                case (daysAgo, _, accuracy) => (daysAgo, accuracy)
              })
            ForecastAccuracy(date, accsByTerminal)
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
