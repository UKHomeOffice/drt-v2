package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions._

class FlightFilterHandler[M](modelRW: ModelRW[M, String]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetFlightFilterMessage(message) => updated(message)
  }
}
