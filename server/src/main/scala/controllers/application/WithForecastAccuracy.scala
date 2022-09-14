package controllers.application

import controllers.Application
import play.api.mvc.{Action, AnyContent}
import services.accuracy.ForecastAccuracyCalculator
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default.write

import scala.concurrent.Future


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
