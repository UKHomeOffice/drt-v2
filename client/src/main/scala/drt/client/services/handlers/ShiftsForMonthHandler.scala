package drt.client.services.handlers

import autowire._
import boopickle.Default._

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode._
import diode.data.{Pending, Pot, Ready}
import drt.client.actions.Actions.{GetShiftsForMonth, RetryActionAfter, SaveMonthTimeSlotsToShifts, SetShiftsForMonth}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.{Api, MonthOfShifts}

import scala.concurrent.Future

class ShiftsForMonthHandler[M](modelRW: ModelRW[M, Pot[MonthOfShifts]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetShiftsForMonth(month, terminalName) =>
      log.info(s"Calling getShifts for Month")

      val apiCallEffect = Effect(AjaxClient[Api].getShiftsForMonth(month.millisSinceEpoch, terminalName).call()
        .map(shiftAssignments => SetShiftsForMonth(MonthOfShifts(month.millisSinceEpoch, shiftAssignments)))
        .recoverWith {
          case t =>
            log.error(s"Failed to get shifts for month. Re-requesting after ${PollDelay.recoveryDelay}: $t")
            Future(RetryActionAfter(GetShiftsForMonth(month, terminalName), PollDelay.recoveryDelay))
        })
      updated(Pending(), apiCallEffect)

    case SetShiftsForMonth(monthOfRawShifts) =>
      updated(Ready(monthOfRawShifts))
  }
}
