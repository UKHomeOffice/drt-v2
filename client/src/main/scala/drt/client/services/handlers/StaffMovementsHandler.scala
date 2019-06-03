package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.Implicits.runAfterImpl
import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{AjaxClient, PollDelay, ViewMode}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Api, StaffMovement}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class StaffMovementsHandler[M](getCurrentViewMode: () => ViewMode,
                               modelRW: ModelRW[M, Pot[Seq[StaffMovement]]]) extends LoggingActionHandler(modelRW) {

  def startMillisFromView: MillisSinceEpoch = getCurrentViewMode().dayStart.millisSinceEpoch

  def viewHasChanged(viewMode: ViewMode): Boolean = viewMode.dayStart.millisSinceEpoch != startMillisFromView

  def scheduledRequest(viewMode: ViewMode): Effect = Effect(Future(GetStaffMovements(viewMode))).after(2 seconds)

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovements(staffMovements) =>
      value match {
        case Ready(sms) =>
          AjaxClient[Api].addStaffMovements(staffMovements).call()
          val newStaffMovements = (sms ++ staffMovements).sortBy(_.time.millisSinceEpoch)
          updated(Ready(newStaffMovements))
        case _ => noChange
      }

    case RemoveStaffMovements(movementsPairUuid) =>
      value match {
        case Ready(sms) =>
          AjaxClient[Api].removeStaffMovements(movementsPairUuid).call()
          val newStaffMovements = sms.filterNot(_.uUID == movementsPairUuid).sortBy(_.time.millisSinceEpoch)
          updated(Ready(newStaffMovements))
        case _ => noChange
      }

    case SetStaffMovements(viewMode, staffMovements: Seq[StaffMovement]) =>
      if (viewMode.isHistoric)
        updated(Ready(staffMovements))
      else
        updated(Ready(staffMovements), scheduledRequest(viewMode))

    case GetStaffMovements(viewMode) if viewHasChanged(viewMode) =>
      log.info(s"Ignoring old view response")
      noChange

    case GetStaffMovements(viewMode) =>
      val maybePointInTimeMillis = if (viewMode.isHistoric) Option(viewMode.millis) else None

      log.info(s"Calling getStaffMovements with ${maybePointInTimeMillis.map(SDate(_).toISOString())}")

      val apiCallEffect = Effect(AjaxClient[Api].getStaffMovements(maybePointInTimeMillis).call()
        .map { res =>
          log.info(s"Got StaffMovements from the server")
          SetStaffMovements(viewMode, res)
        }
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetStaffMovements(viewMode), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
