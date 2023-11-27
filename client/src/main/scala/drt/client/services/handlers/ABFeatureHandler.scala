package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.handlers.ABFeatureRow._
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.ABFeature
import upickle.default._

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetABFeature(functionName:String) extends Action

case class SetABFeature(abFeature: Seq[ABFeature]) extends Action

object ABFeatureRow {
  implicit val rw: ReadWriter[ABFeature] = macroRW
}

class ABFeatureHandler[M](modelRW: ModelRW[M, Pot[Seq[ABFeature]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetABFeature(userFeedbacks) =>
      updated(Ready(userFeedbacks))

    case GetABFeature(functionName) =>
      val apiCallEffect = Effect(DrtApi.get(s"ab-feature/$functionName")
        .map(r => SetABFeature(read[Seq[ABFeature]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get user feedback. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetUserFeedback(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
