package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data.{Empty, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW, NoAction}
import drt.client.actions.Actions.{GetApplicationVersion, SetApplicationVersion, TriggerReload, UpdateServerApplicationVersion}
import drt.client.logger.log
import drt.client.services.{ClientServerVersions, DrtApi, PollDelay}
import drt.shared.BuildVersion
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success, Try}

class ApplicationVersionHandler[M](modelRW: ModelRW[M, Pot[ClientServerVersions]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetApplicationVersion =>
      log.info(s"Calling getApplicationVersion")

      val nextCallEffect = Effect(Future(GetApplicationVersion)).after(PollDelay.recoveryDelay)

      val effect = Effect(DrtApi.get("version").map(response => {
        Try(read[BuildVersion](response.responseText)) match {
          case Success(buildInfo) =>
            value match {
              case Ready(ClientServerVersions(clientVersion, _)) if buildInfo.version != clientVersion && buildInfo.requiresReload =>
                TriggerReload
              case Ready(ClientServerVersions(clientVersion, _)) if buildInfo.version != clientVersion =>
                UpdateServerApplicationVersion(buildInfo.version)
              case Ready(_) =>
                log.info(s"server application version unchanged (${buildInfo.version})")
                NoAction
              case Empty =>
                SetApplicationVersion(buildInfo.version)
              case u =>
                log.info(s"Got a $u")
                NoAction
            }
          case Failure(t) =>
            log.info(s"Failed to parse application version number from response: $t")
            NoAction
        }
      }))

      effectOnly(nextCallEffect + effect)

    case SetApplicationVersion(newVersion) =>
      log.info(s"Setting application version to $newVersion")
      updated(Ready(ClientServerVersions(newVersion, newVersion)))

    case UpdateServerApplicationVersion(newServerVersion) =>
      log.info(s"Updating server application version to $newServerVersion")
      val newClientServerVersions = value.map(_.copy(server = newServerVersion))
      updated(newClientServerVersions)
  }
}
