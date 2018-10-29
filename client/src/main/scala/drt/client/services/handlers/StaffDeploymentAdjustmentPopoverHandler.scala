package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions._
import drt.client.components.StaffDeploymentsAdjustmentPopover.StaffDeploymentAdjustmentPopoverState
import drt.client.logger.log

class StaffDeploymentAdjustmentPopoverHandler[M](popoverState: ModelRW[M, Option[StaffDeploymentAdjustmentPopoverState]]) extends LoggingActionHandler(popoverState) {

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateStaffAdjustmentPopOver(newState) =>
      log.info(s"Updating popover state to $newState")
      updated(newState)
  }
}
