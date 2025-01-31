package drt.client.services.handlers

import diode._
import diode.data.{Pending, Pot, Ready}
import drt.client.actions.Actions.{GetAllShifts, GetAllStaffShifts, SetAllShifts, SetAllStaffShifts}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.ShiftAssignments
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AllStaffAssignmentsHandler[M](modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAllStaffShifts =>
      val apiCallEffect = Effect(DrtApi.get("staff-assignments")
        .map(r => SetAllStaffShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get all shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pending(), apiCallEffect)

    case SetAllStaffShifts(monthOfRawShifts) =>
      updated(Ready(monthOfRawShifts))
  }
}
