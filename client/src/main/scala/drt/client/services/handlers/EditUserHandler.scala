package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.Implicits.runAfterImpl
import diode._
import diode.data.{Pending, Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.Api
import drt.shared.KeyCloakApi.KeyCloakGroup

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class EditUserHandler[M](modelRW: ModelRW[M, Pot[Set[KeyCloakGroup]]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetUserGroups(userId) =>
      log.info(s"Getting groups for $userId")
      effectOnly(Effect(AjaxClient[Api].getKeyCloakUserGroups(userId).call().map(SetSelectedUserGroups).recoverWith {
        case _ =>
          log.error(s"GetUserGroups request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetUserGroups(userId), PollDelay.recoveryDelay))
      }))

    case SetSelectedUserGroups(groups) =>
      updated(Ready(groups), Effect(Future(GetKeyCloakUsers)))

    case SaveUserGroups(userId, groupsToAdd, groupsToRemove) =>
      val futureAddResult: Future[Any] = AjaxClient[Api]
        .addUserToGroups(userId, groupsToAdd)
        .call()

      val futureRemoveResult: Future[Any] = AjaxClient[Api]
        .removeUserFromGroups(userId, groupsToRemove)
        .call()

      val effect = Effect(Future.sequence(List(futureAddResult, futureRemoveResult)).map(_ => GetUserGroups(userId)).recoverWith {
        case _ =>
          log.error(s"AddUserToGroups request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(SaveUserGroups(userId, groupsToAdd, groupsToRemove), PollDelay.recoveryDelay))
      })
      updated(Pending(), effect.after(1 seconds))
  }
}
