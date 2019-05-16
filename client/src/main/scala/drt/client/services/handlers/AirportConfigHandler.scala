package drt.client.services.handlers

import diode.data.{Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetAirportConfig, RetryActionAfter, UpdateAirportConfig}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.AirportConfig
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAirportConfig =>

      updated(Pending(), Effect(DrtApi.get("airport-config")
        .map(r => UpdateAirportConfig(read[AirportConfig](r.responseText))).recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetAirportConfig, PollDelay.recoveryDelay))
      }))
    case UpdateAirportConfig(airportConfig) =>
      updated(Ready(airportConfig))
  }
}
