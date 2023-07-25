package controllers.application

import controllers.Application
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
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
        ForecastAccuracyCalculator(date, daysToCalculate, ctrl.actualPaxNos, ctrl.forecastPaxNos, ctrl.now().toLocalDate)
      }
      maybeResponse match {
        case Some(eventualAccuracy) =>
          eventualAccuracy.map(acc => Ok(write(acc)))
        case None =>
          Future.successful(BadRequest("Invalid date"))
      }
    }
  }

  def forecastAccuracyExport(daysForComparison: Int, daysAhead: Int): Action[AnyContent] = auth {
    Action { _ =>
      val stream = ForecastAccuracyCalculator
        .predictionsVsLegacyForecast(daysForComparison, daysAhead, ctrl.actualArrivals, ctrl.forecastArrivals, ctrl.now().toLocalDate)
        .map {
          case (date, terminal, e) =>
            s"${date.toISOString},${terminal.toString},${maybeDoubleToString(e.predictionRmse)},${maybeDoubleToString(e.legacyRmse)},${maybeDoubleToString(e.predictionError)},${maybeDoubleToString(e.legacyError)}"
        }

      sourceToCsvResponse(stream, "forecast-accuracy.csv")
    }
  }

  private def maybeDoubleToString(rmse: Option[Double]) = {
    rmse.map(_.toString).getOrElse("-")
  }
}
