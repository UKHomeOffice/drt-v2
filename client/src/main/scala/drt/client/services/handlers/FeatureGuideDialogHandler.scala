package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.services.{DrtApi, PollDelay}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import drt.client.logger.log

case class CloseFeatureGuideDialog() extends Action

case class FeatureGuideDialog(showNewFeatureGuideOnLogin: Boolean) extends Action

case class IsNewFeatureAvailable() extends Action

class FeatureGuideDialogHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {
  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case IsNewFeatureAvailable() =>
      val apiCallEffect = Effect(DrtApi.get("is-new-feature-available-since-last-login")
        .map(r => FeatureGuideDialog(r.responseText == "true"))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeatureGuides(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)

    case FeatureGuideDialog(showNewFeatureGuideOnLogin) =>
      updated(Ready(showNewFeatureGuideOnLogin))

    case CloseFeatureGuideDialog() =>
      val apiCallEffect = Effect(DrtApi.get("data/track-user")
        .map(_ => FeatureGuideDialog(false))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeatureGuides(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
