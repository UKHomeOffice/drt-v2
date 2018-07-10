package drt.client.services.handlers

import autowire._
import boopickle.Default._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetLoggedInStatus, RetryActionAfter, TriggerReload}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay, RootModel}
import drt.shared.Api
import org.scalajs.dom
import org.scalajs.dom.ext.AjaxException

import scala.concurrent.Future

class LoggedInStatusHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetLoggedInStatus =>
      effectOnly(
        Effect(
          AjaxClient[Api]
            .isLoggedIn()
            .call()
            .map(_ => RetryActionAfter(GetLoggedInStatus, PollDelay.loginCheckDelay))
            .recoverWith {
              case f: AjaxException if f.xhr.status == 405 =>
                log.error(s"User is logged out, triggering page reload.")
                Future(TriggerReload)
              case f =>
                log.error(s"Error when checking for user login status, retrying after ${PollDelay.loginCheckDelay}")
                Future(RetryActionAfter(GetLoggedInStatus, PollDelay.loginCheckDelay))
            }
        ))

    case TriggerReload =>
      log.info(s"LoginStatus: triggering reload")
      dom.window.location.reload(true)
      noChange
  }
}
