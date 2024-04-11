package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, EffectSingle, ModelRW, NoAction}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared._
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class FixedPointsHandler[M](getCurrentViewMode: () => ViewMode, modelRW: ModelRW[M, Pot[FixedPointAssignments]]) extends LoggingActionHandler(modelRW) {
  def scheduledRequest(viewMode: ViewMode): Effect = Effect(Future(GetFixedPoints(viewMode))).after(2 seconds)

  import JSDateConversions.longToSDateLocal

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetFixedPoints(viewMode, fixedPoints, _) =>
      val isFirstLoad = value.isEmpty
      val isUpdated = fixedPoints != value.getOrElse(FixedPointAssignments.empty)
      if (viewMode.isHistoric(SDate.now()))
        if (isFirstLoad) updated(Ready(fixedPoints))
        else if (isUpdated) updated(Ready(fixedPoints), updateSnackbarEffect())
        else noChange
      else {
        if (isFirstLoad) updated(Ready(fixedPoints), scheduledRequest(viewMode))
        else if (isUpdated) updated(Ready(fixedPoints), scheduledRequest(viewMode) + updateSnackbarEffect())
        else effectOnly(scheduledRequest(viewMode))
      }

    case SaveFixedPoints(assignments, terminal) =>
      val otherTerminalFixedPoints = value.getOrElse(FixedPointAssignments.empty).notForTerminal(terminal)
      val newFixedPoints: FixedPointAssignments = assignments + otherTerminalFixedPoints
      val futureResponse: Future[Action] = DrtApi.post("fixed-points", write(newFixedPoints))
        .map(_ => SetFixedPoints(getCurrentViewMode(), newFixedPoints, Option(terminal.toString)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save FixedPoints. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveFixedPoints(assignments, terminal), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))

    case GetFixedPoints(viewMode) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring old view response")
      noChange

    case GetFixedPoints(viewMode) =>
      val url = "fixed-points" + viewMode.maybePointInTime.map(pit => s"?pointInTime=$pit").getOrElse("")

      val apiCallEffect = Effect(
        DrtApi.get(url)
          .map(r => SetFixedPoints(viewMode, read[FixedPointAssignments](r.responseText), None))
          .recoverWith {
            case _ =>
              log.error(s"Failed to get fixed points. Polling will continue")
              Future(NoAction)
          }
      )
      effectOnly(apiCallEffect)
  }

  private def updateSnackbarEffect(): EffectSingle[SetSnackbarMessage] = {
    Effect(Future(SetSnackbarMessage(Ready("Miscellaneous staff updated"))))
  }
}
