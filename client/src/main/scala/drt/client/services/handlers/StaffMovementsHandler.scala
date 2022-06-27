package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode._
import diode.data.{Pot, Ready}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay, ViewMode}
import drt.shared.{StaffMovement, StaffMovements}
import drt.client.services.JSDateConversions.SDate
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class StaffMovementsHandler[M](getCurrentViewMode: () => ViewMode,
                               modelRW: ModelRW[M, Pot[StaffMovements]]) extends LoggingActionHandler(modelRW) {
  def scheduledRequest(viewMode: ViewMode): Effect = Effect(Future(GetStaffMovements(viewMode))).after(2 seconds)

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovements(staffMovements) =>
      value match {
        case Ready(sms) =>
          val updatedStaffMovements = StaffMovements((sms.movements ++ staffMovements).sortBy(_.time))
          effectOnly(Effect(DrtApi.post("staff-movements", write(staffMovements))
            .map(_ => SetStaffMovements(updatedStaffMovements)).recover {
            case _ =>
              RetryActionAfter(AddStaffMovements(staffMovements), PollDelay.recoveryDelay)
          }))

        case _ => noChange
      }

    case RemoveStaffMovements(movementsPairUuid) =>
      value match {
        case Ready(sms) =>
          val updatedStaffMovements = StaffMovements(sms.movements.filterNot(_.uUID == movementsPairUuid).sortBy(_.time))
          effectOnly(Effect(DrtApi.delete(s"staff-movements/$movementsPairUuid")
            .map(_ => SetStaffMovements(updatedStaffMovements)).recover {
            case _ =>
              RetryActionAfter(RemoveStaffMovements(movementsPairUuid), PollDelay.recoveryDelay)
          }))

        case _ => noChange
      }
    case SetStaffMovements(sms) =>
      updated(Ready(sms))

    case SetStaffMovementsAndPollIfLiveView(viewMode, staffMovements) =>
      if (viewMode.isHistoric(SDate.now()))
        updated(Ready(staffMovements))
      else
        updated(Ready(staffMovements), scheduledRequest(viewMode))

    case GetStaffMovements(viewMode) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring old view response")
      noChange

    case GetStaffMovements(viewMode) =>
      val uri = if (viewMode.isHistoric(SDate.now())) s"staff-movements?pointInTime=${viewMode.millis}" else "staff-movements"

      val apiCallEffect = Effect(DrtApi.get(uri)
        .map(res => {
          SetStaffMovementsAndPollIfLiveView(viewMode, StaffMovements(read[List[StaffMovement]](res.responseText)))
        })
        .recover {
          case _ =>
            log.error(s"Failed to get Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
            RetryActionAfter(GetStaffMovements(viewMode), PollDelay.recoveryDelay)
        })

      effectOnly(apiCallEffect)
  }
}
