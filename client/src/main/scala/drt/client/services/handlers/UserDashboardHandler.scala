package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.BorderForceStaff
import drt.client.actions.Actions._
import drt.client.services.ViewLive

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class UserDashboardHandler[M](modelRW: ModelRW[M, Pot[LoggedInUser]]) extends LoggingActionHandler(modelRW) {

  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetUserDashboardState =>
      value match {
        case Ready(potUser) if potUser.hasRole(BorderForceStaff) =>
          effectOnly(Effect(Future(SetViewMode(ViewLive))))
        case Ready(_) =>
          noChange
        case _ =>
          effectOnly(Effect(Future(RetryActionAfter(GetUserDashboardState, 1 second))))
      }
  }
}
