package drt.client.services.handlers

import autowire._
import boopickle.Default._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetUserRoles, RetryActionAfter, SetUserRoles}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.Api

import scala.concurrent.Future

class UserRolesHandler[M](modelRW: ModelRW[M, Pot[List[String]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetUserRoles =>
      effectOnly(Effect(AjaxClient[Api].getUserRoles().call().map(SetUserRoles).recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetUserRoles, PollDelay.recoveryDelay))
      }))

    case SetUserRoles(roles) =>
      log.info(s"Roles: $roles")
      updated(Ready(roles))
  }
}
