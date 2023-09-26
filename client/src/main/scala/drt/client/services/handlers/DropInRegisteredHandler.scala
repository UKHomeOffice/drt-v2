package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.{DropIn, DropInRegistration}
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class RegisterDropIns(id: String) extends Action

case class GetRegisteredDropIns(email:String) extends Action

case class SetRegisteredDropIns(dropInRegistrations: Seq[DropInRegistration]) extends Action

class DropInRegisteredHandler[M](modelRW: ModelRW[M, Pot[Seq[DropInRegistration]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetRegisteredDropIns(registeredDropIns) =>
      updated(Ready(registeredDropIns))

    case GetDropIns() =>
      val apiCallEffect = Effect(DrtApi.get("drop-ins")
        .map(r => SetDropIns(read[Seq[DropIn]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetDropIns(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)

    case RegisterDropIns(id: String) =>
      val apiCallEffect = Effect(DrtApi.post(s"register-drop-in", write(id))
        .recoverWith {
          case _ =>
            log.error(s"Failed to register drop-ins. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(RegisterDropIns(id), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)

    case GetRegisteredDropIns(email: String) =>
      val withoutQuotes = email.replaceAll("\"", "")
      val apiCallEffect = Effect(DrtApi.get(s"register-drop-in/" + withoutQuotes)
        .map(r => SetRegisteredDropIns(read[Seq[DropInRegistration]](r.responseText)))
          .recoverWith {
            case _ =>
              log.error(s"Failed to get registered drop-ins for email $email. Re-requesting after ${PollDelay.recoveryDelay}")
              Future(RetryActionAfter(GetRegisteredDropIns(email), PollDelay.recoveryDelay))
          })

        effectOnly (apiCallEffect)
  }
}
