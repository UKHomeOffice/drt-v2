package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.components.NationalityFlaggerState

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FlaggedNationalitiesHandler[M](modelRW: ModelRW[M, NationalityFlaggerState]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case AddFlaggedNationality(country) =>
      updated(value.copy(flaggedNationalities = value.flaggedNationalities + country), Effect(Future.successful(UpdateNationalityFlaggerInputText(""))))
    case RemoveFlaggedNationality(country) =>
      updated(value.copy(flaggedNationalities = value.flaggedNationalities - country))
    case ClearFlaggedNationalities =>
      updated(value.copy(flaggedNationalities = Set()))
    case SetNationalityFlaggerOpen(open) =>
      updated(value.copy(open = open))
    case UpdateNationalityFlaggerInputText(text) =>
      updated(value.copy(inputText = text))
  }
}
