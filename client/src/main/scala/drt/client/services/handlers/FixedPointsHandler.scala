package drt.client.services.handlers

import autowire._
import boopickle.Default._

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions._
import drt.client.components.FixedPoints
import drt.client.logger.log
import drt.client.services._
import drt.shared.Api
import drt.shared.FlightsApi.TerminalName

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class FixedPointsHandler[M](viewMode: () => ViewMode, modelRW: ModelRW[M, Pot[String]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetFixedPoints(fixedPoints: String, terminalName: Option[String]) =>
      if (terminalName.isDefined)
        updated(Ready(fixedPoints))
      else
        updated(Ready(fixedPoints))

    case SaveFixedPoints(fixedPoints: String, terminalName: TerminalName) =>
      log.info(s"Calling saveFixedPoints")

      val otherTerminalFixedPoints = FixedPoints.filterOtherTerminals(terminalName, value.getOrElse(""))
      val newRawFixedPoints = otherTerminalFixedPoints + "\n" + fixedPoints
      val futureResponse = AjaxClient[Api].saveFixedPoints(newRawFixedPoints).call()
        .map(_ => SetFixedPoints(newRawFixedPoints, Option(terminalName)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to save FixedPoints. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveFixedPoints(fixedPoints, terminalName), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(futureResponse))

    case AddShift(fixedPoints) =>
      updated(Ready(s"${value.getOrElse("")}\n${fixedPoints.toCsv}"))

    case GetFixedPoints() =>
      val fixedPointsEffect = Effect(Future(GetFixedPoints())).after(60 minutes)
      log.info(s"Calling getFixedPoints")

      val apiCallEffect = Effect(AjaxClient[Api].getFixedPoints(viewMode().millis).call().map(res => SetFixedPoints(res, None)))
      effectOnly(apiCallEffect + fixedPointsEffect)
  }
}
