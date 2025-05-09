package drt.client.services.handlers

import diode._
import diode.data.{Pending, Pot, Ready}
import drt.client.actions.Actions.{GetAllShiftAssignments, RetryActionAfter, SetAllShiftAssignments, UpdateShiftAssignments}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.ShiftAssignments
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AllShiftAssignmentsHandler[M](modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAllShiftAssignments =>
      val apiCallEffect = Effect(DrtApi.get("staff-assignments")
        .map(r => SetAllShiftAssignments(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get all shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pending(), apiCallEffect)

    case SetAllShiftAssignments(monthOfRawShifts) =>
      updated(Ready(monthOfRawShifts))

    case UpdateShiftAssignments(assignments) =>
      val futureResponse = DrtApi.post("staff-assignments", write(ShiftAssignments(assignments)))
        .map(r => SetAllShiftAssignments(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save Shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateShiftAssignments(assignments), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))
  }
}
