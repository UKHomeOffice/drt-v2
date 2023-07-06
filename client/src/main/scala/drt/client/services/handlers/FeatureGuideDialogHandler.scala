package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.services.{DrtApi, PollDelay}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import drt.client.logger.log

case class CloseFeatureGuideDialog() extends Action
case class FeatureGuideDialog(toggleDialog: Boolean) extends Action
case class IsNewFeatureAvailable() extends Action
class FeatureGuideDialogHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {

  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case IsNewFeatureAvailable() => {
      val apiCallEffect = Effect(DrtApi.get("isNewFeatureAvailableSinceLastLogin")
        .map(r => FeatureGuideDialog(r.responseText match {
          case "true" => true
          case _ => false
        }))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeatureGuides(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
    }

    case FeatureGuideDialog(toggleDialog) => {
      updated(Ready(toggleDialog))
    }

    case CloseFeatureGuideDialog() => {
      val apiCallEffect = Effect(DrtApi.get("data/user-tracking")
        .map(_ => FeatureGuideDialog(false))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeatureGuides(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
    }
  }
}
