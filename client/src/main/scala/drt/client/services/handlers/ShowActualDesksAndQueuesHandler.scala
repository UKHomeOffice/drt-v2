package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions.UpdateShowActualDesksAndQueues

class ShowActualDesksAndQueuesHandler[M](modelRW: ModelRW[M, Boolean]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateShowActualDesksAndQueues(state) =>
      updated(state)
  }
}
