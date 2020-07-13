package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import diode.data.Pot
import drt.client.actions.Actions._
import drt.shared.SimulationResult
import org.scalajs.dom._

class SimulationHandler[M](simulationResult: ModelRW[M, Pot[SimulationResult]]) extends LoggingActionHandler(simulationResult) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SimulationExport(simulation) =>

    noChange

  }
}
