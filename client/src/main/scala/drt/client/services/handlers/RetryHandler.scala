package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW}
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions.RetryActionAfter
import drt.client.services.RootModel

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RetryHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case RetryActionAfter(actionToRetry, delay) =>
      effectOnly(Effect(Future(actionToRetry)).after(delay))
  }
}
