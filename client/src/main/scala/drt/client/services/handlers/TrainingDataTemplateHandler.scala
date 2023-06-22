package drt.client.services.handlers

import diode.data.{Empty, Pot, Ready, Unavailable}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{RetryActionAfter, SetSnackbarMessage}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.training.TrainingData
import TrainingData._

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue


case class GetTrainingDataTemplates() extends Action

case class SetTrainingDataTemplates(trainingDataTemplates: Seq[TrainingData]) extends Action

case class SetTrainingDataTemplatesEmpty() extends Action

class TrainingDataTemplateHandler[M](modelRW: ModelRW[M, Pot[Seq[TrainingData]]]) extends LoggingActionHandler(modelRW) {
  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case SetTrainingDataTemplatesEmpty() =>
      println("SetTrainingDataTemplatesEmpty......")
      updated(Empty)

    case SetTrainingDataTemplates(trainingDataTemplates) =>
      println("SetTrainingDataTemplates......")
      updated(Ready(trainingDataTemplates))

    case GetTrainingDataTemplates() =>
      println("GetTrainingDataTemplates......")
      val apiCallEffect = Effect(DrtApi.get("training-data")
        .map(r => SetTrainingDataTemplates(getTrainingDataConversion(r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get training data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetTrainingDataTemplates(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
