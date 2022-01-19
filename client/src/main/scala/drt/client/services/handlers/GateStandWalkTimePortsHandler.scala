package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{UpdateGateStandWalktime, _}
import drt.client.logger.log
import drt.client.services.DrtApi
import drt.shared.api.WalkTimes
import upickle.default.read
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class GateStandWalkTimePortsHandler[M](modelRW: ModelRW[M, Pot[WalkTimes]]) extends LoggingActionHandler(modelRW) {
  val requestFrequency: FiniteDuration = 60.seconds

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetGateStandWalktime =>
      val apiCallEffect = effectOnly(Effect(DrtApi.get(s"walk-times")
        .map { response =>
          val walkTimes: WalkTimes = read[WalkTimes](response.responseText)
          UpdateGateStandWalktime(walkTimes)
        }
      ))
      apiCallEffect


    case UpdateGateStandWalktime(walkTimes) =>
      log.info(s"Received stand and gate walkTimes.")
      updated(Ready(walkTimes))

  }
}
