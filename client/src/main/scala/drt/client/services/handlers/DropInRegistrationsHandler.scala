package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.DropInRegistration
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class CreateDropInRegistration(id: String) extends Action

case class GetDropInRegistrations() extends Action

case class SetDropInRegistrations(dropInRegistrations: Seq[DropInRegistration]) extends Action

class DropInRegistrationsHandler[M](modelRW: ModelRW[M, Pot[Seq[DropInRegistration]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetDropInRegistrations(registeredDropIns) =>
      updated(Ready(registeredDropIns))

    case CreateDropInRegistration(id: String) =>
      val apiCallEffect = Effect(DrtApi.post(s"drop-in-registrations", write(id))
        .recoverWith {
          case _ =>
            log.error(s"Failed to register drop-ins. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(CreateDropInRegistration(id), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)

    case GetDropInRegistrations() =>
      val apiCallEffect = Effect(DrtApi.get(s"drop-in-registrations")
        .map(r => SetDropInRegistrations(read[Seq[DropInRegistration]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get registered drop-ins. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetDropInRegistrations(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
