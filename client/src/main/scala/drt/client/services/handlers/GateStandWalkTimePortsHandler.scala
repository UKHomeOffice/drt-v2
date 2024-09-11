package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{UpdateGateStandWalktime, _}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.api.WalkTimes
import upickle.default.read

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class GateStandWalkTimePortsHandler[M](modelRW: ModelRW[M, Pot[WalkTimes]]) extends LoggingActionHandler(modelRW) {
  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetGateStandWalktime =>
      effectOnly(Effect(DrtApi.get(s"walk-times")
        .map { response =>
          val walkTimes: WalkTimes = read[WalkTimes](response.responseText)
          UpdateGateStandWalktime(walkTimes)
        }
        .recoverWith {
          case _ =>
            log.error(s"Error while getting Gate and Stand walk time")
            Future(UpdateGateStandWalktime(WalkTimes(Map.empty)))
        })
      )

    case UpdateGateStandWalktime(walkTimes) =>
      updated(Ready(walkTimes))
  }
}
