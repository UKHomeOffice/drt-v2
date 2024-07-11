package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions.{RequestDateRecrunch, RequestForecastRecrunch, RequestMissingHistoricSplits, RequestMissingPaxNos, RequestRecalculateArrivals}
import drt.client.services.{DrtApi, RootModel}
import upickle.default.write

class AppControlHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case RequestDateRecrunch(date) =>
      DrtApi.post(s"control/crunch/recalculate/${date.toISOString}/${date.toISOString}", "")
      noChange

    case RequestForecastRecrunch(recalculateSplits) =>
      DrtApi.post("control/crunch/recalculate", write(recalculateSplits))
      noChange

    case RequestRecalculateArrivals =>
      DrtApi.post("control/arrivals/recalculate", write(true))
      noChange

    case RequestMissingHistoricSplits =>
      DrtApi.post("control/historic-splits/lookup-missing", write(true))
      noChange

    case RequestMissingPaxNos =>
      DrtApi.post("control/pax-nos/lookup-missing", write(true))
      noChange
  }
}
