package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.api.WalkTimes
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class WalkTimeHandler[M](modelRW: ModelRW[M, Pot[WalkTimes]]) extends LoggingActionHandler(modelRW) {

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetWalktimes =>
      effectOnly(Effect(DrtApi.get(s"walk-times")
        .map { response =>
          val walktimes = read[WalkTimes](response.responseText)
          SetWalktimes(walktimes)
        }
        .recoverWith {
          case _ => Future(RetryActionAfter(GetAirportConfig, PollDelay.recoveryDelay))
        }))
    case SetWalktimes(wt) =>
      updated(Ready(wt))

  }
}
