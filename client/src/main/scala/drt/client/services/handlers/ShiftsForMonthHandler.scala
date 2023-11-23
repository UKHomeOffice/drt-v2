package drt.client.services.handlers

import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions.{GetShiftsForMonth, SetShiftsForMonth}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.MonthOfShifts
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ShiftsForMonthHandler[M](modelRW: ModelRW[M, Pot[MonthOfShifts]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetShiftsForMonth(month) =>
      val url = s"shifts-for-month/${month.millisSinceEpoch}"
      val apiCallEffect = Effect(DrtApi.get(url)
        .map(r => SetShiftsForMonth(read[MonthOfShifts](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get shifts for month: ${t.getMessage}")
            Future(NoAction)
        })
      effectOnly(apiCallEffect)

    case SetShiftsForMonth(monthOfRawShifts) =>
      updated(Ready(monthOfRawShifts))
  }
}
