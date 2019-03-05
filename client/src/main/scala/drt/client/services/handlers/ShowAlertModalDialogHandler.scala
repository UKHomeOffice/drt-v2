package drt.client.services.handlers

import autowire._
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{RetryActionAfter, _}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.Api
import boopickle.Default._

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ShowAlertModalDialogHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateShowAlertModalDialog(show) => updated(Ready(show))

    case GetShowAlertModalDialog =>
      effectOnly(
        Effect(
          AjaxClient[Api]
            .getShowAlertModalDialog()
            .call()
            .map(show => UpdateShowAlertModalDialog(show))
            .recoverWith {
              case f =>
                log.error(s"Error when checking for modal dialog feature switch. $f")
                Future(RetryActionAfter(GetShowAlertModalDialog, PollDelay.recoveryDelay))
            }
        ))
  }
}
