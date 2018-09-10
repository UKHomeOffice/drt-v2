package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW, NoAction}
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions.{GetShifts, SetShifts}
import drt.client.logger.log
import drt.client.services.{AjaxClient, ViewMode}
import drt.shared.{Api, StaffAssignments}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ShiftsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[StaffAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetShifts(shifts, _) => updated(Ready(shifts))

    case GetShifts() =>
      val fixedPointsEffect = Effect(Future(GetShifts())).after(1 minute)
      log.info(s"Calling getShifts")

      val apiCallEffect = Effect(
        AjaxClient[Api].getShifts(viewMode().millis).call()
          .map(res => SetShifts(res, None))
          .recoverWith {
            case _ =>
              log.error(s"Failed to get shifts. Polling will continue")
              Future(NoAction)
          }
      )
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}
