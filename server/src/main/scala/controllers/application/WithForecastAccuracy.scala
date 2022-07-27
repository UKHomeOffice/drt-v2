package controllers.application

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import controllers.Application
import drt.shared.api.ForecastAccuracy
import play.api.mvc.{Action, AnyContent}
import services.AccuracyForDate
import services.accuracy.ForecastAccuracyCalculator
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import upickle.default.write

import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}


trait WithForecastAccuracy {
  self: Application =>

  def getForecastAccuracy(dateStr: String): Action[AnyContent] = auth {
    val daysToCalculate = List(1, 3, 7, 14, 30)

    Action.async { _ =>
      val maybeResponse = for {
        date <- LocalDate.parse(dateStr)
      } yield {
        ForecastAccuracyCalculator(date, daysToCalculate, ctrl.actualPaxNos, ctrl.forecastPaxNos,ctrl.now().toLocalDate)
      }
      maybeResponse match {
        case Some(eventualAccuracy) =>
          eventualAccuracy.map(acc => Ok(write(acc)))
        case None =>
          Future.successful(BadRequest("Invalid date"))
      }
    }
  }
}
