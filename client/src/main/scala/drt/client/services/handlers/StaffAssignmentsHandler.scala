package drt.client.services.handlers

import diode.AnyAction.aType
import diode.Implicits.runAfterImpl
import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{DrtApi, PollDelay, ViewMode}
import drt.shared.{ShiftAssignments, StaffAssignment}
import uk.gov.homeoffice.drt.time.LocalDate
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class UpdateStaffShiftsWithSummary(shiftsToUpdate: Seq[StaffAssignment],
                                        portCode: String,
                                        terminal: String,
                                        localDate: LocalDate,
                                        interval: Int,
                                        dayRange: String) extends Action

class StaffAssignmentsHandler[M](getCurrentViewMode: () => ViewMode, modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  def scheduledRequest(viewMode: ViewMode): Effect = Effect(Future(GetShifts(viewMode))).after(2 seconds)

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetStaffAssignments(viewMode, shifts, _) =>
      if (viewMode.isHistoric(SDate.now()))
        updated(Ready(shifts))
      else
        updated(Ready(shifts), scheduledRequest(viewMode))

    case GetStaffAssignments(viewMode) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring old view response")
      noChange

    case GetStaffAssignments(viewMode) =>
      val url = s"staff-assignments/${viewMode.localDate.toISOString}" +
        viewMode.maybePointInTime.map(pit => s"?pointInTime=$pit").getOrElse("")

      val apiCallEffect: EffectSingle[Action] = Effect(
        DrtApi.get(url)
          .map(r => SetStaffAssignments(viewMode, read[ShiftAssignments](r.responseText), None))
          .recoverWith {
            case _ =>
              log.error(s"Failed to get fixed points. Polling will continue")
              Future(NoAction)
          }
      )
      effectOnly(apiCallEffect)

    case UpdateStaffShifts(assignments) =>
      val futureResponse = DrtApi.post("staff-assignments", write(ShiftAssignments(assignments)))
        .map(r => SetAllStaffShifts(read[ShiftAssignments](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save Shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateStaffShifts(assignments), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))

    case UpdateStaffShiftsWithSummary(assignments, portCode, terminal, localDate, interval, dayRange) =>
      def futureResponse = DrtApi.post("staff-assignments", write(ShiftAssignments(assignments)))
        .map { r =>
          val shiftAssignments = read[ShiftAssignments](r.responseText)
          SetAllStaffShifts(shiftAssignments)
          UpdateShiftSummaryStaffingWithAssignment(shiftAssignments, portCode, terminal, localDate, interval, dayRange)
        }
        .recoverWith {
          case _ =>
            log.error(s"Failed to save Shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateStaffShifts(assignments), PollDelay.recoveryDelay))
        }

      effectOnly(Effect(futureResponse))
  }
}
