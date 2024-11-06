package drt.client.services.handlers

import diode._
import diode.data.{Pending, Pot, Ready}
import drt.client.actions.Actions.{GetAllShifts, SetAllShifts}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.ShiftAssignments
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AllShiftsHandler[M](modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAllShifts =>
      val apiCallEffect = Effect(DrtApi.get("shifts")
        .map(r => SetAllShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get all shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pending(), apiCallEffect)

    case SetAllShifts(monthOfRawShifts) =>
      updated(Ready(monthOfRawShifts))
  }
}
