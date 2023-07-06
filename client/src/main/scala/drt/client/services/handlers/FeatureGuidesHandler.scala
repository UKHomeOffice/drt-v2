package drt.client.services.handlers

import diode.data.{Empty, Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.training.FeatureGuide
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetFeatureGuides() extends Action

case class SetFeatureGuides(trainingDataTemplates: Seq[FeatureGuide]) extends Action

class FeatureGuidesHandler[M](modelRW: ModelRW[M, Pot[Seq[FeatureGuide]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetFeatureGuides(trainingDataTemplates) =>
      updated(Ready(trainingDataTemplates))

    case GetFeatureGuides() =>
      val apiCallEffect = Effect(DrtApi.get("feature-guides")
        .map(r => SetFeatureGuides(FeatureGuide.getFeatureGuideConversion(r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeatureGuides(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
