package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions.DoNothing
import drt.client.services.RootModel

class NoopHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case DoNothing() =>
      noChange
  }
}
