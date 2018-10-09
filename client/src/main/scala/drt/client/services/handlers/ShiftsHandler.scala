package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.data.{Pot, Ready}
import diode._
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions.{GetShifts, RetryActionAfter, SetShifts, UpdateShifts}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay, ViewLive, ViewMode}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Api, ShiftAssignments}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ShiftsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[ShiftAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetShifts(shifts, _) => updated(Ready(shifts))

    case GetShifts() =>
      val fixedPointsEffect = Effect(Future(GetShifts())).after(1 minute)
      log.info(s"Calling getShifts")

      val maybePointInTimeMillis: Option[MillisSinceEpoch] = viewMode() match {
        case ViewLive() => None
        case vm: ViewMode => Option(vm.millis)
      }
      val apiCallEffect = Effect(
        AjaxClient[Api].getShifts(maybePointInTimeMillis).call()
          .map(res => SetShifts(res, None))
          .recoverWith {
            case _ =>
              log.error(s"Failed to get shifts. Polling will continue")
              Future(NoAction)
          }
      )
      effectOnly(apiCallEffect + fixedPointsEffect)

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
