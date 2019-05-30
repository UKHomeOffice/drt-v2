package drt.client.services.handlers

import diode.{ActionHandler, ActionResult, ModelRW}
import drt.client.logger.log

import scala.util.{Failure, Success, Try}

abstract class LoggingActionHandler[M, T](modelRW: ModelRW[M, T]) extends ActionHandler(modelRW) {
  override def handleAction(model: M, action: Any): Option[ActionResult[M]] = {
    Try(super.handleAction(model, action)) match {
      case Failure(f) =>
        f.getCause match {
          case null => log.error(s"no cause: $f, ${f.getStackTrace.mkString("\n")}")
          case c => log.error(s"Exception from $getClass  ${c.getMessage}")
        }

        throw f
      case Success(s) =>
        s
    }
  }
}
