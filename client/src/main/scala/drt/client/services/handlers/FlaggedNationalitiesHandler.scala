package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.components.Country

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FlaggedNationalitiesHandler[M](modelRW: ModelRW[M, Set[Country]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddFlaggedNationality(country) =>
      updated(value + country, Effect(Future.successful(UpdateNationalityFlaggerInputText(""))))
    case RemoveFlaggedNationality(country) =>
      updated(value - country)
    case ClearFlaggedNationalities =>
      updated(Set())
  }
}
