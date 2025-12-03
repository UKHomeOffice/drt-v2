package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetCodeShareExceptions, RetryActionAfter, UpdateCodeShareExceptions}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.arrivals.FlightCode
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class CodeshareExceptionsConfigHandler[M](modelRW: ModelRW[M, Set[FlightCode]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetCodeShareExceptions =>
      effectOnly(Effect(DrtApi.get("config/codeshare-exceptions")
        .map(r => UpdateCodeShareExceptions(read[Set[String]](r.responseText).map(FlightCode(_)))).recoverWith {
        case _ =>
          log.error(s"GetCodeShareExceptions request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetCodeShareExceptions, PollDelay.recoveryDelay))
      }))

    case UpdateCodeShareExceptions(codeShareExceptions) =>
      updated(codeShareExceptions)
  }
}
