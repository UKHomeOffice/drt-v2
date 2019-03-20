package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW, NoAction}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class FixedPointsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[FixedPointAssignments]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetFixedPoints(fixedPoints, _) => updated(Ready(fixedPoints))

    case SaveFixedPoints(assignments, terminalName) =>
      log.info(s"Calling saveFixedPoints")

      val otherTerminalFixedPoints = value.getOrElse(FixedPointAssignments.empty).notForTerminal(terminalName)
      val newFixedPoints: FixedPointAssignments = assignments + otherTerminalFixedPoints
      val futureResponse = AjaxClient[Api].saveFixedPoints(newFixedPoints).call()
        .map(_ => SetFixedPoints(newFixedPoints, Option(terminalName)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save FixedPoints. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveFixedPoints(assignments, terminalName), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))

    case GetFixedPoints() =>
      val fixedPointsEffect = Effect(Future(GetFixedPoints())).after(60 minutes)
      log.info(s"Calling getFixedPoints")

      val maybePointInTimeMillis: Option[MillisSinceEpoch] = viewMode() match {
        case ViewLive() => None
        case vm: ViewMode => Option(vm.millis)
      }
      val apiCallEffect = Effect(
        AjaxClient[Api].getFixedPoints(maybePointInTimeMillis).call()
          .map(res => SetFixedPoints(res, None))
          .recoverWith {
            case _ =>
              log.error(s"Failed to get fixed points. Polling will continue")
              Future(NoAction)
          }
      )
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}
