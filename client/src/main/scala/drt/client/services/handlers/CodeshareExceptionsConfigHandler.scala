package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetAirportConfig, RetryActionAfter, UpdateCodeShareExceptions}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.arrivals.FlightCode
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class CodeshareExceptionsConfigHandler[M](modelRW: ModelRW[M, Set[FlightCode]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetAirportConfig =>
      effectOnly(Effect(DrtApi.get("config/codeshare-exceptions")
        .map(r => UpdateCodeShareExceptions(read[Set[String]](r.responseText).map(FlightCode(_)))).recoverWith {
        case _ =>
          log.error(s"AirportConfig request failed. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetAirportConfig, PollDelay.recoveryDelay))
      }))

    case UpdateCodeShareExceptions(codeShareExceptions) =>
      updated(codeShareExceptions)
  }
}
