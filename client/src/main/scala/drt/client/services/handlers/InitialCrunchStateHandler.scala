package drt.client.services.handlers

import diode._
import diode.data._
import drt.client.actions.Actions._
import drt.client.logger._
import drt.client.services._
import drt.shared.CrunchApi._
import org.scalajs.dom
import upickle.default.read

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class InitialCrunchStateHandler[M](viewMode: () => ViewMode,
                                   modelRW: ModelRW[M, (Pot[CrunchState], MillisSinceEpoch)]) extends LoggingActionHandler(modelRW) {
  val crunchUpdatesRequestFrequency: FiniteDuration = 2 seconds

  def startMillisFromView: MillisSinceEpoch = viewMode().dayStart.millisSinceEpoch

  def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetInitialCrunchState() =>
      val startMillis = startMillisFromView
      val endMillis = startMillis + (1000 * 60 * 60 * 36)
      val updateRequestFuture = DrtApi.get(s"crunch-state?sinceMillis=0&windowStartMillis=$startMillis&windowEndMillis=$endMillis")

      val eventualAction = processRequest(startMillis, updateRequestFuture)

      updated((Pending(), 0L), Effect(Future(ShowLoader())) + Effect(eventualAction))

    case CreateCrunchStateFromUpdates(startMillis, _) if startMillis != startMillisFromView =>
      log.warn(s"Received old crunch state response ($startMillis != $startMillisFromView)")
      noChange

    case CreateCrunchStateFromUpdates(_, crunchUpdates) =>
      val newState = CrunchState(crunchUpdates.flights, crunchUpdates.minutes, crunchUpdates.staff)
      updated((Ready(newState), crunchUpdates.latest), Effect(Future(HideLoader())))
  }

  def processRequest(startMillis: MillisSinceEpoch, call: Future[dom.XMLHttpRequest]): Future[Action] = {
    call
      .map(r => read[Either[CrunchStateError, Option[CrunchUpdates]]](r.responseText))
      .map {
        case Right(Some(cu)) => CreateCrunchStateFromUpdates(startMillis, cu)
        case Right(None) => NoAction
        case Left(error) =>
          log.error(s"Failed to GetInitialCrunchState ${error.message}. Re-requesting after ${PollDelay.recoveryDelay}")
          RetryActionAfter(GetInitialCrunchState(), PollDelay.recoveryDelay)
      }
      .recoverWith {
        case throwable =>
          log.error(s"Call to crunch-state failed (${throwable.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetInitialCrunchState(), PollDelay.recoveryDelay))
      }
  }

}
