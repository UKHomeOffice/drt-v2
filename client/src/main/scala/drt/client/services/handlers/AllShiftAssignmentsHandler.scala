package drt.client.services.handlers

import diode._
import diode.data.{Pending, Pot, Ready}
import drt.client.actions.Actions.{GetAllStaffAssignments, RetryActionAfter, SetAllStaffShifts, UpdateStaffShifts}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.ShiftAssignments
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AllShiftAssignmentsHandler[M](modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAllStaffAssignments =>
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

    case UpdateStaffShifts(assignments) =>
      val futureResponse = DrtApi.post("staff-assignments", write(ShiftAssignments(assignments)))
        .map(r => SetAllStaffShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save Shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateStaffShifts(assignments), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))
  }
}
