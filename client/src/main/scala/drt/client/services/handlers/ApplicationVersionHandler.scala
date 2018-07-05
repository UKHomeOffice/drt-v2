package drt.client.services.handlers

import autowire._
import boopickle.Default._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Empty, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW, NoAction}
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions.{GetApplicationVersion, SetApplicationVersion, UpdateServerApplicationVersion}
import drt.client.logger.log
import drt.client.services.{AjaxClient, ClientServerVersions, PollDelay}
import drt.shared.Api

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class ApplicationVersionHandler[M](modelRW: ModelRW[M, Pot[ClientServerVersions]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetApplicationVersion =>
      log.info(s"Calling getApplicationVersion")

      val nextCallEffect = Effect(Future(GetApplicationVersion)).after(PollDelay.recoveryDelay)

      val effect = Effect(AjaxClient[Api].getApplicationVersion().call().map(serverVersionContent => {
        Try(Integer.parseInt(serverVersionContent)) match {
          case Success(serverVersionInt) =>
            val serverVersion = serverVersionInt.toString
            value match {
              case Ready(ClientServerVersions(clientVersion, _)) if serverVersion != clientVersion =>
                UpdateServerApplicationVersion(serverVersion)
              case Ready(_) =>
                log.info(s"server application version unchanged ($serverVersionInt)")
                NoAction
              case Empty =>
                SetApplicationVersion(serverVersion)
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
