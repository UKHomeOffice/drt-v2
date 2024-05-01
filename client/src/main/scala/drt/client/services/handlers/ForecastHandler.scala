package drt.client.services.handlers

import diode.data.{Pot, Ready, Unavailable}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetForecastWeek, RetryActionAfter, SetForecastPeriod}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.CrunchApi.ForecastPeriodWithHeadlines
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ForecastHandler[M](modelRW: ModelRW[M, Pot[ForecastPeriodWithHeadlines]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetForecastWeek(startDay, terminalName, forecastPeriod) =>
      log.info(s"Calling forecastWeekSummary starting at ${startDay.toLocalDateTimeString} for forecastPeriod $forecastPeriod.")
      val apiCallEffect = Effect(DrtApi.get(s"forecast-summary/$terminalName/${startDay.millisSinceEpoch}/$forecastPeriod")
        .map(res => SetForecastPeriod(read[Option[ForecastPeriodWithHeadlines]](res.responseText)))
        .recoverWith {
          case t =>
            log.error(s"Failed to get Forecast Period: ${t.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetForecastWeek(startDay, terminalName, forecastPeriod), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)

    case SetForecastPeriod(Some(forecastPeriod)) =>
      log.info(s"Received forecast period.")
      updated(Ready(forecastPeriod))

    case SetForecastPeriod(None) =>
      log.info(s"No forecast available for requested dates")
      updated(Unavailable)
  }
}
