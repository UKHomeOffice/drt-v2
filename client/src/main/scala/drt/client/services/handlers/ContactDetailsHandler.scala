package drt.client.services.handlers

import diode.data.{Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetContactDetails, RetryActionAfter, UpdateContactDetails}
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.ContactDetails
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ContactDetailsHandler[M](modelRW: ModelRW[M, Pot[ContactDetails]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetContactDetails =>

      updated(Pending(), Effect(DrtApi.get("contact-details")
        .map(r => UpdateContactDetails(read[ContactDetails](r.responseText))).recoverWith {
        case _ =>
          Future(RetryActionAfter(GetContactDetails, PollDelay.recoveryDelay))
      }))
    case UpdateContactDetails(contactDetails) =>
      updated(Ready(contactDetails))
  }
}
