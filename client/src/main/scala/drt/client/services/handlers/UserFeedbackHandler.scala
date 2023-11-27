package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import upickle.default.{macroRW, read, readwriter, write}
import upickle.default._
import UserFeedbackRow._
import uk.gov.homeoffice.drt.feedback.UserFeedback

case class CloseBanner(feedbackType: String, aORbTest: String) extends Action

case class GetUserFeedback() extends Action

case class SetUserFeedback(userFeedback: Seq[UserFeedback]) extends Action

object UserFeedbackRow {
  implicit val rw: ReadWriter[UserFeedback] = macroRW
}

class UserFeedbackHandler[M](modelRW: ModelRW[M, Pot[Seq[UserFeedback]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case CloseBanner(feedbackType, aORbTest) =>
      val apiCallEffect = Effect(DrtApi.post(s"close-banner/$feedbackType/$aORbTest", "")
        .map(_ => GetUserFeedback())
        .recoverWith {
          case _ =>
            log.error(s"Failed to get user feedback. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetUserFeedback(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)

    case SetUserFeedback(userFeedbacks) =>
      updated(Ready(userFeedbacks))

    case GetUserFeedback() =>
      val apiCallEffect = Effect(DrtApi.get(s"user-feedback")
        .map(r => SetUserFeedback(read[Seq[UserFeedback]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get user feedback. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetUserFeedback(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
