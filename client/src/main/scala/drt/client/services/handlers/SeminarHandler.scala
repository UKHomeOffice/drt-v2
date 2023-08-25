package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.{Seminar, SeminarRegistration}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class RegisterSeminars(ids: Seq[String]) extends Action

case class GetSeminars() extends Action

case class SetSeminars(seminars: Seq[Seminar]) extends Action

class SeminarHandler[M](modelRW: ModelRW[M, Pot[Seq[Seminar]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetSeminars(seminars) =>
      updated(Ready(seminars))

    case GetSeminars() =>
      val apiCallEffect = Effect(DrtApi.get("seminars")
        .map(r => SetSeminars(Seminar.deserializeFromJsonString(r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetSeminars(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)

    case RegisterSeminars(ids:Seq[String]) =>
      val apiCallEffect = Effect(DrtApi.post(s"register-seminar", SeminarRegistration.serializeIds(ids))
        .recoverWith {
          case _ =>
            log.error(s"Failed to register seminars. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(RegisterSeminars(ids), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
