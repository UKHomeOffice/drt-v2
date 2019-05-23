package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.Implicits.runAfterImpl
import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay, ViewMode}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Api, ShiftAssignments}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ShiftsHandler[M](getCurrentViewMode: () => ViewMode, modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {

  def startMillisFromView: MillisSinceEpoch = getCurrentViewMode().dayStart.millisSinceEpoch

  def viewHasChanged(viewMode: ViewMode): Boolean = viewMode.dayStart.millisSinceEpoch != startMillisFromView

  def scheduledRequest(viewMode: ViewMode): Effect = Effect(Future(GetShifts(viewMode))).after(2 seconds)

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetShifts(viewMode, shifts, _) =>
      if (viewMode.isHistoric)
        updated(Ready(shifts))
      else
        updated(Ready(shifts), scheduledRequest(viewMode))

    case GetShifts(viewMode) if viewHasChanged(viewMode) =>
      log.info(s"Ignoring old view response")
      noChange

    case GetShifts(viewMode) =>
      val maybePointInTimeMillis = if (viewMode.isHistoric) Option(viewMode.millis) else None

      val apiCallEffect = Effect(AjaxClient[Api].getShifts(maybePointInTimeMillis).call()
        .map(res => SetShifts(viewMode, res, None))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get shifts. Polling will continue")
            Future(NoAction)
        })
      effectOnly(apiCallEffect)

    case UpdateShifts(shiftsToUpdate) =>
      log.info(s"Saving staff time slots as Shifts")
      val action: Future[Action] = AjaxClient[Api].updateShifts(shiftsToUpdate).call().map(_ => NoAction)
        .recoverWith {
          case error =>
            log.error(s"Failed to save staff month timeslots: $error, retrying after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateShifts(shiftsToUpdate), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(action))
  }
}
