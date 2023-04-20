package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions.{RequestForecastRecrunch, RequestRecalculateArrivals}
import drt.client.services.{DrtApi, RootModel}
import upickle.default.write

import scala.language.postfixOps


class AppControlHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case RequestForecastRecrunch(recalculateSplits) =>
      DrtApi.post("control/crunch/recalculate", write(recalculateSplits))
      noChange
    case RequestRecalculateArrivals =>
      DrtApi.post("control/arrivals/recalculate", write(true))
      noChange
  }
}
