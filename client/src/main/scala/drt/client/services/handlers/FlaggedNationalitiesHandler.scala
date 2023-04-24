package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions.{AddFlaggedNationality, RemoveFlaggedNationality}
import drt.client.components.Country

class FlaggedNationalitiesHandler[M](modelRW: ModelRW[M, Set[Country]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddFlaggedNationality(country) =>
      updated(value + country)
    case RemoveFlaggedNationality(country) =>
      updated(value - country)
  }
}
