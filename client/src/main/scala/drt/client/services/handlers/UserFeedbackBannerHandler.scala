package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import scala.concurrent.Future

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class SetViewBanner(showFeedbackBanner: Boolean) extends Action

case class CloseBanner() extends Action

case class ShouldViewBanner() extends Action

class UserFeedbackBannerHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {
  override protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case ShouldViewBanner() =>
      val apiCallEffect = Effect(DrtApi.get("data/should-user-view-banner")
        .map(r => SetViewBanner(r.responseText == "true"))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get view banner data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(ShouldViewBanner(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)

    case SetViewBanner(status) =>
      println(s"setting view banner to $status")
      updated(Ready(status))

    case CloseBanner() =>
      val apiCallEffect = Effect(DrtApi.post("data/close-banner", "")
        .map(_ => SetViewBanner(false))
        .recoverWith {
          case _ =>
            log.error(s"Failed to close banner with api. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(CloseBanner(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
