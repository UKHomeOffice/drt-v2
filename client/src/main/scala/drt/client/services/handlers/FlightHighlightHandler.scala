package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions.UpdateFlightHighlight
import drt.shared.FlightHighlight

class FlightHighlightHandler[M](modelRW: ModelRW[M, FlightHighlight]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateFlightHighlight(flightHighlight) =>
      updated(flightHighlight)
  }
}
