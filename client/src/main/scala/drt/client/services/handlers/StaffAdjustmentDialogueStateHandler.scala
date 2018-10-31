package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions._
import drt.client.components.StaffAdjustmentDialogueState

class StaffAdjustmentDialogueStateHandler[M](popoverState: ModelRW[M, Option[StaffAdjustmentDialogueState]]) extends LoggingActionHandler(popoverState) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateStaffAdjustmentDialogueState(newState) => updated(newState)
  }
}
