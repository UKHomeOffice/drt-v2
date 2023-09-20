package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.{DropIn}
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class RegisterDropIns(id: String) extends Action

case class GetDropIns() extends Action

case class SetDropIns(dropIns: Seq[DropIn]) extends Action

class DropInHandler[M](modelRW: ModelRW[M, Pot[Seq[DropIn]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetDropIns(dropIns) =>
      updated(Ready(dropIns))

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
  }
}
