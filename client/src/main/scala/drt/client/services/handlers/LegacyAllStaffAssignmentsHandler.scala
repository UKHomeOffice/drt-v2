package drt.client.services.handlers

import diode._
import diode.data.{Pending, Pot, Ready}
import drt.client.actions.Actions.{GetAllLegacyStaffAssignments, SetAllLegacyStaffAssignments}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.ShiftAssignments
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class LegacyAllStaffAssignmentsHandler[M](modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAllLegacyStaffAssignments =>
      val apiCallEffect = Effect(DrtApi.get("legacy-staff-assignments")
        .map(r => SetAllLegacyStaffAssignments(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case t =>
            log.error(msg = s"Failed to get all shifts: ${t.getMessage}")
            Future(NoAction)
        })
      updated(Pending(), apiCallEffect)

    case SetAllLegacyStaffAssignments(monthOfRawShifts) =>
      updated(Ready(monthOfRawShifts))
  }
}
