package drt.client.services.handlers

import diode.data.{Empty, Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.SimulationResult
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class SimulationHandler[M](simulationResult: ModelRW[M, Pot[SimulationResult]]) extends LoggingActionHandler(simulationResult) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetSimulation(params) =>

      updated(Pending(), Effect(DrtApi.get(s"desk-rec-simulation?${params.toQueryStringParams}")
        .map(r => SetSimulation(read[SimulationResult](r.responseText))).recoverWith {
        case _ =>
          Future(RetryActionAfter(GetSimulation(params), PollDelay.recoveryDelay))
      }))
    case SetSimulation(simulation) =>

      updated(Ready(simulation))
    case ReSetSimulation =>

      updated(Empty)
  }
}
