package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetLoggedInStatus, RetryActionAfter, TriggerReload}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay, RootModel}
import org.scalajs.dom
import org.scalajs.dom.ext.AjaxException

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class LoggedInStatusHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetLoggedInStatus =>
      effectOnly(
        Effect(
          DrtApi.get("logged-in")
            .map(r => {
              if(r.status == 200)
                RetryActionAfter(GetLoggedInStatus, PollDelay.loginCheckDelay)
              else
                TriggerReload
            })
            .recoverWith {
              case f: AjaxException if f.xhr.status == 405 || f.xhr.status == 401 =>
                log.error(s"User is logged out. Triggering page reload.")
                Future(TriggerReload)
              case f =>
                log.error(s"Unexpected error when checking for user login status ($f). Retrying after ${PollDelay.loginCheckDelay}")
                Future(RetryActionAfter(GetLoggedInStatus, PollDelay.loginCheckDelay))
            }
        ))

    case TriggerReload =>
      log.info(s"LoginStatus: triggering reload")
      dom.window.location.reload(true)
      noChange
  }
}
