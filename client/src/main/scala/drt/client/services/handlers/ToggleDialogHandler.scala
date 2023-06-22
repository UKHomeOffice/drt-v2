package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, ModelRW}

case class CloseTrainingDialog() extends Action
case class TrainingDialog(toggleDialog: Boolean) extends Action

class ToggleDialogHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {

  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case TrainingDialog(toggleDialog) => {
      println(s"TrainingDialog......$toggleDialog")
      updated(Ready(toggleDialog))
    }

    case CloseTrainingDialog() => {
      println(s"CloseTrainingDialog......")
      updated(Ready(false))
    }
  }
}
