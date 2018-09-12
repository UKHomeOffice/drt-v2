package drt.client.services.handlers

import autowire._
import boopickle.Default._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Empty, Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetAirportConfig, RetryActionAfter, UpdateAirportConfig}
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared.{AirportConfig, Api}
import scala.concurrent.Future

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case _: GetAirportConfig =>
      log.info(s"Calling airportConfiguration")
      updated(Pending(), Effect(AjaxClient[Api].airportConfiguration().call().map(UpdateAirportConfig).recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetAirportConfig(), PollDelay.recoveryDelay))
      }))
    case UpdateAirportConfig(airportConfigOption) =>
      val pot = airportConfigOption.map(Ready(_)).getOrElse(Empty)
      updated(pot)
  }
}
