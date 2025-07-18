package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, RootModel}
import upickle.default.write

class AppControlHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case RequestDateDeskRecsRecalculation(date) =>
      DrtApi.post(s"control/desk-recs/recalculate/${date.toISOString}/${date.toISOString}", "")
      noChange

    case RequestDatePaxLoadsRecalculation(date) =>
      DrtApi.post(s"control/pax-loads/recalculate/${date.toISOString}/${date.toISOString}", "")
      noChange

    case RequestRecalculateSplits =>
      DrtApi.post("control/splits/recalculate", write(true))
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
