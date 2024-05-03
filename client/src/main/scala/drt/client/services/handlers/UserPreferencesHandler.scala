package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class SetSelectedTimeInterval(previousInterval: Int) extends Action

case class SendSelectedTimeInterval(interval: Int) extends Action

case class GetUserPreferenceIntervalMinutes() extends Action

class UserPreferencesHandler[M](modelRW: ModelRW[M, Pot[Int]]) extends LoggingActionHandler(modelRW) {

  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetUserPreferenceIntervalMinutes() =>
      val apiCallEffect = Effect(DrtApi.get("data/user-preference-planning-interval-minutes")
        .map(r => SetSelectedTimeInterval(r.responseText match {
          case "15" => 15
          case _ => 60
        }))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Previous Time PeriodSelected data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetUserPreferenceIntervalMinutes(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)

    case SetSelectedTimeInterval(previousPeriodInterval) => updated(Ready(previousPeriodInterval))

    case SendSelectedTimeInterval(interval) =>
      val apiCallEffect = Effect(DrtApi.put(s"data/user-preference-planning-interval-minutes", s"${interval.toString}")
        .map(_ => SetSelectedTimeInterval(interval))
        .recoverWith {
          case _ =>
            log.error(s"Failed to send time period selected data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SendSelectedTimeInterval(interval), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
