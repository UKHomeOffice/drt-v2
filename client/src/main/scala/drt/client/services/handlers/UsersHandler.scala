package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.Api
import drt.shared.KeyCloakApi.KeyCloakUser

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class UsersHandler[M](modelRW: ModelRW[M, Pot[List[KeyCloakUser]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetKeyCloakUsers =>
      effectOnly(Effect(AjaxClient[Api].getKeyCloakUsers().call().map(SetKeyCloakUsers).recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetKeyCloakUsers, PollDelay.recoveryDelay))
      }))

    case SetKeyCloakUsers(users) =>
      updated(Ready(users))

    case AddUserToGroup(userId, groupName) =>
      effectOnly(Effect(AjaxClient[Api].addUserToGroup(userId, groupName).call().map(_ => GetKeyCloakUsers).recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(AddUserToGroup(userId, groupName), PollDelay.recoveryDelay))
      }))
  }
}
