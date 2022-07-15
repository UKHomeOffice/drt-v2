package drt.client.services.handlers

import diode.data.{Empty, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.api.ForecastAccuracy
import upickle.default.read

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ForecastAccuracyHandler[M](modelRW: ModelRW[M, Pot[ForecastAccuracy]]) extends LoggingActionHandler(modelRW) {
  val requestFrequency: FiniteDuration = 60.seconds

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetForecastAccuracy(date) =>
      updated(Empty, Effect(DrtApi.get(s"forecast-accuracy/$date")
        .map { response =>
          val forecastAccuracy: ForecastAccuracy = read[ForecastAccuracy](response.responseText)
          UpdateForecastAccuracy(forecastAccuracy)
        }.recoverWith {
        case _ =>
          log.error(s"Error getting forecast accuracy for $date")
          Future(UpdateForecastAccuracy(ForecastAccuracy(date, Map())))
      }))

    case UpdateForecastAccuracy(accuracy) =>
      log.info(s"Received forecast accuracy stats")
      updated(Ready(accuracy))
  }
}
