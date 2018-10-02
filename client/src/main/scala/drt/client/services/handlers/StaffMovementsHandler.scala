package drt.client.services.handlers

import autowire._
import boopickle.Default._

import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{AjaxClient, PollDelay, ViewMode}
import drt.shared.{Api, StaffMovement}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class StaffMovementsHandler[M](modelRW: ModelRW[M, (Pot[Seq[StaffMovement]], ViewMode)]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovements(staffMovements) =>
      value match {
        case (Ready(sms), vm) =>
          AjaxClient[Api].addStaffMovements(staffMovements).call()
          val newStaffMovements = (sms ++ staffMovements).sortBy(_.time.millisSinceEpoch)
          updated((Ready(newStaffMovements), vm))
        case _ => noChange
      }

    case RemoveStaffMovements(movementsPairUuid) =>
      value match {
        case (Ready(sms), vm) =>
          AjaxClient[Api].removeStaffMovements(movementsPairUuid).call()
          val newStaffMovements = sms.filterNot(_.uUID == movementsPairUuid).sortBy(_.time.millisSinceEpoch)
          updated((Ready(newStaffMovements), vm))
        case _ => noChange
      }

    case SetStaffMovements(staffMovements: Seq[StaffMovement]) =>
      val scheduledRequest = Effect(Future(GetStaffMovements())).after(60 seconds)
      updated((Ready(staffMovements), value._2), scheduledRequest)

    case GetStaffMovements() =>
      val (_, viewMode) = value
      log.info(s"Calling getStaffMovements with ${SDate(viewMode.millis).toISOString()}")


      val apiCallEffect = Effect(AjaxClient[Api].getStaffMovements(viewMode.millis).call()
        .map(res => {
          log.info(s"Got StaffMovements from the server")
          SetStaffMovements(res)
        })
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetStaffMovements(), PollDelay.recoveryDelay))
        }
      )
      effectOnly(apiCallEffect)
  }
}
