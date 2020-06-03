package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.auth.{BorderForceStaff, LoggedInUser}
import drt.client.actions.Actions._
import drt.client.services.JSDateConversions.SDate
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
          effectOnly(Effect(Future(SetViewMode(ViewLive(SDate.now().millisSinceEpoch)))))
        case Ready(_) =>
          noChange
        case _ =>
          effectOnly(Effect(Future(RetryActionAfter(GetUserDashboardState, 1 second))))
      }
  }
}
