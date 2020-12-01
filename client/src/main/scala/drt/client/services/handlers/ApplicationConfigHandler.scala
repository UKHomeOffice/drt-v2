package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetApplicationConfig, ScheduleAction, SetApplicationConfig}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.ApplicationConfig
import upickle.default.read

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success, Try}


class ApplicationConfigHandler[M](modelRW: ModelRW[M, Pot[ApplicationConfig]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetApplicationConfig =>
      log.info(s"Calling application-config")

      val effect = Effect(DrtApi.get("application-config").map(response => {
        Try(read[ApplicationConfig](response.responseText)) match {
          case Success(config: ApplicationConfig) =>
            SetApplicationConfig(config)
          case Failure(t) =>
            log.info(s"Failed to parse application version number from response: $t")
            ScheduleAction(PollDelay.recoveryDelay, GetApplicationConfig)
        }
      }))

      effectOnly(effect)

    case SetApplicationConfig(newConfig) =>
      log.info(s"Setting application config to $newConfig")
      updated(Ready(newConfig))
  }
}
