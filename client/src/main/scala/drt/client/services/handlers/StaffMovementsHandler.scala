package drt.client.services.handlers

import autowire._
import boopickle.Default._

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{AjaxClient, PollDelay, ViewMode}
import drt.shared.{Api, SDateLike, StaffAssignment, StaffMovement}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class StaffMovementsHandler[M](modelRW: ModelRW[M, (Pot[Seq[StaffMovement]], ViewMode)]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddStaffMovement(staffMovement) =>
      value match {
        case (Ready(sms), vm) =>

          val updatedValue: Seq[StaffMovement] = (sms :+ staffMovement).sortBy(_.time.millisSinceEpoch)
          updated((Ready(updatedValue), vm))
        case _ => noChange
      }


    case RemoveStaffMovement(_, uUID) =>
      value match {
        case (Ready(sms), vm) if sms.exists(_.uUID == uUID) =>

          sms.find(_.uUID == uUID).map(_.terminalName).map(terminal => {
            val updatedValue = sms.filter(_.uUID != uUID)
            updated((Ready(updatedValue), vm), Effect(Future(SaveStaffMovements(terminal))))
          })
            .getOrElse(noChange)

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

    case SaveStaffMovements(t) =>
      value match {
        case (Ready(sms), vm) =>
          log.info(s"Calling saveStaffMovements")
          val responseFuture = AjaxClient[Api].saveStaffMovements(sms).call()
            .map(_ => DoNothing())
            .recoverWith {
              case _ =>
                log.error(s"Failed to save Staff Movements. Re-requesting after ${PollDelay.recoveryDelay}")
                Future(RetryActionAfter(SaveStaffMovements(t), PollDelay.recoveryDelay))
            }
          effectOnly(Effect(responseFuture))
        case _ =>
          noChange
      }
  }
}
