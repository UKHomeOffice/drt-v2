package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.{Api, LoggedInUser}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class LoggedInUserHandler[M](modelRW: ModelRW[M, Pot[LoggedInUser]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetLoggedInUser =>
      effectOnly(Effect(AjaxClient[Api].getLoggedInUser().call().map(SetLoggedInUser).recoverWith {
        case _ =>
          log.info(s"GetLoggedInUser request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetLoggedInUser, PollDelay.recoveryDelay))
      }))

    case SetLoggedInUser(loggedInUser) =>
      log.info(s"LoggedInUser is: $loggedInUser")
      updated(Ready(loggedInUser))


  }
}
