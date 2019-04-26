package drt.client.services.handlers

import diode.data.{Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.SPAMain
import drt.client.actions.Actions.{GetAirportConfig, RetryActionAfter, UpdateAirportConfig}
import drt.client.logger.log
import drt.client.services.PollDelay
import drt.shared.AirportConfig
import org.scalajs.dom
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AirportConfigHandler[M](modelRW: ModelRW[M, Pot[AirportConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAirportConfig =>
      log.info(s"Calling airportConfiguration")
      val url = SPAMain.absoluteUrl("airport-config")

      val eventualAction: Future[UpdateAirportConfig] = dom.ext.Ajax.get(url = url).map(r => {


        val airportConfig = read[AirportConfig]({
          log.info(s"respone: ${r.responseText}")
          r.responseText
        })
        log.info(s"Airport Config is: $airportConfig")
        UpdateAirportConfig(airportConfig)
      })
      updated(Pending(), Effect(eventualAction.recoverWith {
        case _ =>
          log.error(s"CrunchState request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetAirportConfig, PollDelay.recoveryDelay))
      }))
    case UpdateAirportConfig(airportConfig) =>
      log.info(s"Setting Airport config to: $airportConfig")
      updated(Ready(airportConfig))
  }
}
