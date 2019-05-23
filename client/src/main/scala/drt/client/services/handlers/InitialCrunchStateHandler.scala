package drt.client.services.handlers

import diode._
import diode.Implicits.runAfterImpl
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

class InitialCrunchStateHandler[M](getCurrentViewMode: () => ViewMode,
                                   modelRW: ModelRW[M, (Pot[CrunchState], MillisSinceEpoch)]) extends LoggingActionHandler(modelRW) {
  val crunchUpdatesRequestFrequency: FiniteDuration = 2 seconds

  def startMillisFromView: MillisSinceEpoch = getCurrentViewMode().dayStart.millisSinceEpoch

  def viewHasChanged(viewMode: ViewMode): Boolean = viewMode.dayStart.millisSinceEpoch != startMillisFromView

  def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetInitialCrunchState(viewMode) =>
      val startMillis = startMillisFromView
      val endMillis = startMillis + (1000 * 60 * 60 * 36)
      val updateRequestFuture = DrtApi.get(s"crunch-state?sinceMillis=0&windowStartMillis=$startMillis&windowEndMillis=$endMillis")

      val eventualAction = processRequest(viewMode, updateRequestFuture)

      updated((Pending(), 0L), Effect(Future(ShowLoader())) + Effect(eventualAction))

    case CreateCrunchStateFromUpdates(viewMode, _) if viewHasChanged(viewMode) =>
      log.info(s"Ignoring old view response")
      noChange

    case CreateCrunchStateFromUpdates(viewMode, crunchUpdates) =>
      val newState = CrunchState(crunchUpdates.flights, crunchUpdates.minutes, crunchUpdates.staff)
      val originCodes = crunchUpdates.flights.map(_.apiFlight.Origin)

      val hideLoader = Effect(Future(HideLoader()))
      val fetchOrigins = Effect(Future(GetAirportInfos(originCodes)))

      val effects = if (getCurrentViewMode().isHistoric) hideLoader + fetchOrigins else {
        log.info(s"Starting to poll for crunch updates")
        hideLoader + fetchOrigins + getCrunchUpdatesAfterDelay(viewMode)
      }

      updated((Ready(newState), crunchUpdates.latest), effects)
  }

  def processRequest(viewMode: ViewMode, call: Future[dom.XMLHttpRequest]): Future[Action] = {
    call
      .map(r => read[Either[CrunchStateError, Option[CrunchUpdates]]](r.responseText))
      .map {
        case Right(Some(cu)) => CreateCrunchStateFromUpdates(viewMode, cu)
        case Right(None) =>
          log.info(s"Got no crunch state for date")
          CreateCrunchStateFromUpdates(viewMode, CrunchUpdates(0L, Set(), Set(), Set()))
        case Left(error) =>
          log.error(s"Failed to GetInitialCrunchState ${error.message}. Re-requesting after ${PollDelay.recoveryDelay}")
          RetryActionAfter(GetInitialCrunchState(viewMode), PollDelay.recoveryDelay)
      }
      .recoverWith {
        case throwable =>
          log.error(s"Call to crunch-state failed (${throwable.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetInitialCrunchState(viewMode), PollDelay.recoveryDelay))
      }
  }

  def getCrunchUpdatesAfterDelay(viewMode: ViewMode): Effect = Effect(Future(GetCrunchStateUpdates(viewMode))).after(crunchUpdatesRequestFrequency)
}
