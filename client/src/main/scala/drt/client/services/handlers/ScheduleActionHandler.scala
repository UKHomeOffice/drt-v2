package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetApplicationConfig, ScheduleAction, SetApplicationConfig}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.ApplicationConfig
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success, Try}


class ScheduleActionHandler[M, V](modelRW: ModelRW[M, V]) extends LoggingActionHandler(modelRW) {
  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case ScheduleAction(delay, action) =>
      effectOnly(Effect(Future(action)).after(delay))
  }
}

