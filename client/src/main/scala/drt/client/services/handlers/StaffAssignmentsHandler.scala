package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{DrtApi, PollDelay, ViewMode}
import drt.shared.ShiftAssignments
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class StaffAssignmentsHandler[M](getCurrentViewMode: () => ViewMode, modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  def scheduledRequest(viewMode: ViewMode): Effect = Effect(Future(GetShifts(viewMode))).after(2 seconds)

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetStaffShifts(viewMode, shifts, _) =>
      if (viewMode.isHistoric(SDate.now()))
        updated(Ready(shifts))
      else
        updated(Ready(shifts), scheduledRequest(viewMode))

    case GetStaffShifts(viewMode) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring old view response")
      noChange

    case GetStaffShifts(viewMode) =>
      val url = s"staff-assignments/${viewMode.localDate.toISOString}" +
        viewMode.maybePointInTime.map(pit => s"?pointInTime=$pit").getOrElse("")

      val apiCallEffect: EffectSingle[Action] = Effect(
        DrtApi.get(url)
          .map(r => SetStaffShifts(viewMode, read[ShiftAssignments](r.responseText), None))
          .recoverWith {
            case _ =>
              log.error(s"Failed to get fixed points. Polling will continue")
              Future(NoAction)
          }
      )
      effectOnly(apiCallEffect)

    case UpdateStaffShifts(assignments) =>
      val futureResponse = DrtApi.post("staff-assignments", write(ShiftAssignments(assignments)))
        .map(r => SetAllShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save Shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateStaffShifts(assignments), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))
  }
}
