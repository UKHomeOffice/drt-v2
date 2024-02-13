package controllers.application

import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.accuracy.ForecastAccuracyCalculator
import uk.gov.homeoffice.drt.actor.PredictionModelActor
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.prediction.persistence.Flight
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default.write

import scala.concurrent.Future


class ForecastAccuracyController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

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
            f"${date.toISOString},${terminal.toString},${maybeDoubleToPctString(e.predictionRmse)},${maybeDoubleToPctString(e.legacyRmse)},${maybeDoubleToPctString(e.predictionError)},${maybeDoubleToPctString(e.legacyError)}\n"
        }
        .prepend(Source(List("Date,Terminal,Prediction RMSE,Legacy RMSE,Prediction Error,Legacy Error\n")))

      sourceToCsvResponse(stream, "forecast-accuracy.csv")
    }
  }

  private def maybeDoubleToPctString(double: Option[Double]): String =
    double.map(d => f"${d * 100}%.3f").getOrElse("-")

  def forecastModelComparison(modelNames: String, terminalName: String) = auth {
    Action.async { _ =>
      val terminal = Terminal(terminalName)
      val id = PredictionModelActor.Terminal(terminalName)
      val modelNamesList = modelNames.split(",").toList
      val getModelsForId: PredictionModelActor.WithId => Future[PredictionModelActor.Models] = Flight().getModels(modelNamesList)
      getModelsForId(id).map { models =>
        models.models.map { case (_, model: ArrivalModelAndFeatures) =>
          model.prediction()
        }
      }
    }
  }
}
