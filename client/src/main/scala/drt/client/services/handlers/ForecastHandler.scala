package drt.client.services.handlers

import autowire._
import boopickle.Default._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Pot, Ready, Unavailable}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetForecastWeek, RetryActionAfter, SetForecastPeriod}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.Api
import drt.shared.CrunchApi.ForecastPeriodWithHeadlines

import scala.concurrent.Future

class ForecastHandler[M](modelRW: ModelRW[M, Pot[ForecastPeriodWithHeadlines]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetForecastWeek(startDay, terminalName) =>
      log.info(s"Calling forecastWeekSummary starting at ${startDay.toLocalDateTimeString()}")
      val apiCallEffect = Effect(AjaxClient[Api].forecastWeekSummary(startDay.millisSinceEpoch, terminalName).call()
        .map(res => SetForecastPeriod(res))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Forecast Period. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetForecastWeek(startDay, terminalName), PollDelay.recoveryDelay))
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
