package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.services.{DrtApi, PollDelay}
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import drt.client.logger.log

case class GetViewedFeatureCount() extends Action

case class SetViewedFeatureCount(ids: Seq[String]) extends Action

class ViewedFeatureGuidesHandler[M](modelRW: ModelRW[M, Pot[Seq[String]]]) extends LoggingActionHandler(modelRW) {

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewedFeatureCount(ids) =>
      updated(Ready(ids))

    case GetViewedFeatureCount() =>
      val apiCallEffect = Effect(DrtApi.get("viewed-feature-guides")
        .map(r => SetViewedFeatureCount(read[List[String]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get viewed feature guides. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetViewedFeatureCount(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
