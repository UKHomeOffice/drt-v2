package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.services.{DrtApi, PollDelay}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import drt.client.logger.log

case class CloseTrainingDialog() extends Action
case class TrainingDialog(toggleDialog: Boolean) extends Action
case class IsNewFeatureAvailable() extends Action
class ToggleDialogHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {

  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case IsNewFeatureAvailable() => {
      val apiCallEffect = Effect(DrtApi.get("isNewFeatureAvailableSinceLastLogin")
        .map(r => TrainingDialog(r.responseText match {
          case "true" => true
          case _ => false
        }))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetTrainingDataTemplates(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
    }

    case TrainingDialog(toggleDialog) => {
      updated(Ready(toggleDialog))
    }

    case CloseTrainingDialog() => {
      val apiCallEffect = Effect(DrtApi.get("data/user-tracking")
        .map(_ => TrainingDialog(false))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetTrainingDataTemplates(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
    }
  }
}
