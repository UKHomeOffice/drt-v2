package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.DropInRegistration
import upickle.default.{read, write}
//import uk.gov.homeoffice.drt.db.UserFeedbackRow
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetUserFeedback() extends Action

class UserFeedbackHandler[M](modelRW: ModelRW[M, Pot[Seq[DropInRegistration]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetUserFeedback() =>
      val apiCallEffect = Effect(DrtApi.get(s"user-feedback")
//        .map(r => SetDropInRegistrations(read[Seq[UserFeedbackRow]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get registered drop-ins. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetUserFeedback(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
