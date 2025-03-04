package drt.client.services.handlers

import diode.data.{Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.OutOfHoursStatus
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class OohForSupportHandler[M](modelRW: ModelRW[M, Pot[OutOfHoursStatus]]) extends PotActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetOohStatus =>
      effectOnly(Effect(DrtApi.get("ooh-status")
        .map(r => UpdateOohStatus(read[OutOfHoursStatus](r.responseText))).recoverWith {
        case _ =>
          Future(RetryActionAfter(GetOohStatus, PollDelay.recoveryDelay))
      }))

    case UpdateOohStatus(oohStatus) =>
      val poll = Effect(Future(RetryActionAfter(GetOohStatus, PollDelay.oohSupportUpdateDelay)))
      updateIfChanged(oohStatus, poll)
  }
}
