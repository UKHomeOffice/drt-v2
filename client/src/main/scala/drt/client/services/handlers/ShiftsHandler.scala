package drt.client.services.handlers

import autowire._
import boopickle.Default._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions.{GetShifts, RetryActionAfter, SetShifts}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay, ViewMode}
import drt.shared.Api

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ShiftsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
//  implicit val picklerSAs = generatePickler[MillisSinceEpoch]

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetShifts(shifts: String) =>
      val scheduledRequest = Effect(Future(GetShifts())).after(15 seconds)

      updated(Ready(shifts), scheduledRequest)

    case GetShifts() =>
      log.info(s"Calling getShifts")

      val apiCallEffect = Effect(AjaxClient[Api].getShifts(viewMode().millis).call()
        .map(SetShifts)
        .recoverWith {
          case _ =>
            log.error(s"Failed to get shifts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetShifts(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
