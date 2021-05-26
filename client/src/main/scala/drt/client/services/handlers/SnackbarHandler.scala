package drt.client.services.handlers

import diode._
import diode.data.Pot
import drt.client.actions.Actions._

import scala.language.postfixOps

class SnackbarHandler[M](modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetSnackbarMessage(message) => updated(message)
  }
}
