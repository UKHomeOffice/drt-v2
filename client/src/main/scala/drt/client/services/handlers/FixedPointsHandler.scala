package drt.client.services.handlers

import autowire._
import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.components.FixedPoints
import drt.client.logger.log
import drt.client.services._
import drt.shared.{Api, StaffAssignment, StaffAssignments}
import boopickle.Default._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class FixedPointsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[StaffAssignments]]) extends LoggingActionHandler(modelRW) {
//  implicit val picklerSA = generatePickler[StaffAssignment]
//  implicit val picklerSAs = generatePickler[StaffAssignments]

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetFixedPoints(fixedPoints: StaffAssignments, terminalName: Option[String]) =>
      if (terminalName.isDefined)
        updated(Ready(fixedPoints))
      else
        updated(Ready(fixedPoints))

    case SaveFixedPoints(assignments, terminalName) =>
      log.info(s"Calling saveFixedPoints")

      val otherTerminalFixedPoints = FixedPoints.filterOtherTerminals(terminalName, value.getOrElse(StaffAssignments.empty))
      val newFixedPoints = otherTerminalFixedPoints + assignments
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

      val apiCallEffect = Effect(AjaxClient[Api].getFixedPoints(viewMode().millis).call().map(res => SetFixedPoints(res, None)))
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}
