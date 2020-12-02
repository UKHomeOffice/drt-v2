package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.ScheduleAction

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue


class ScheduleActionHandler[M, V](modelRW: ModelRW[M, V]) extends LoggingActionHandler(modelRW) {
  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case ScheduleAction(delay, action) =>
      effectOnly(Effect(Future(action)).after(delay))
  }
}

