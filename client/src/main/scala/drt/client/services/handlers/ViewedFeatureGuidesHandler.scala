package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.services.{DrtApi, PollDelay}
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import drt.client.logger.log

case class GetViewedFeatureIds() extends Action

case class SetViewedFeatureIds(ids: Seq[String]) extends Action

class ViewedFeatureGuidesHandler[M](modelRW: ModelRW[M, Pot[Seq[String]]]) extends LoggingActionHandler(modelRW) {

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewedFeatureIds(ids) =>
      updated(Ready(ids))

    case GetViewedFeatureIds() =>
      val apiCallEffect = Effect(DrtApi.get("viewed-feature-guides")
        .map(r => SetViewedFeatureIds(read[List[String]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get viewed feature guides. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetViewedFeatureIds(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
