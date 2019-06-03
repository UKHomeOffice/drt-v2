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

  val thirtySixHoursInMillis: Long = 1000L * 60 * 60 * 36

  def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetInitialCrunchState(viewMode) =>
      val startMillis = viewMode.dayStart.millisSinceEpoch
      val endMillis = startMillis + thirtySixHoursInMillis
      val updateRequestFuture = viewMode match {
        case ViewPointInTime(time) => DrtApi.get(s"crunch-snapshot/${time.millisSinceEpoch}?start=$startMillis&end=$endMillis")
        case _ => DrtApi.get(s"crunch?start=$startMillis&end=$endMillis")
      }

      val eventualAction = processRequest(viewMode, updateRequestFuture)

      updated((Pending(), 0L), Effect(Future(ShowLoader())) + Effect(eventualAction))

    case CreateCrunchStateFromPortState(viewMode, _) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring out of date view response")
      noChange

    case CreateCrunchStateFromPortState(viewMode, portState) =>
      log.info(s"Got a crunch state!")
      val flights = portState.flights.values.toSet
      val newState = CrunchState(flights, portState.crunchMinutes.values.toSet, portState.staffMinutes.values.toSet)
      val originCodes = flights.map(_.apiFlight.Origin)

      val hideLoader = Effect(Future(HideLoader()))
      val fetchOrigins = Effect(Future(GetAirportInfos(originCodes)))

      val effects = if (getCurrentViewMode().isHistoric) {
        hideLoader + fetchOrigins
      } else {
        log.info(s"Starting to poll for crunch updates")
        hideLoader + fetchOrigins + getCrunchUpdatesAfterDelay(viewMode)
      }

      updated((Ready(newState), portState.latestUpdate), effects)
  }

  def processRequest(viewMode: ViewMode, call: Future[dom.XMLHttpRequest]): Future[Action] = {
    call
      .map(r => read[Either[CrunchStateError, Option[PortState]]](r.responseText))
      .map {
        case Right(Some(portState)) => CreateCrunchStateFromPortState(viewMode, portState)
        case Right(None) =>
          log.info(s"Got no crunch state for date")
          CreateCrunchStateFromPortState(viewMode, PortState(Map(), Map(), Map()))
        case Left(error) =>
          log.error(s"Failed to GetInitialCrunchState ${error.message}")

          if (viewMode.isDifferentTo(getCurrentViewMode())) {
            log.info(s"No need to request as view has changed")
            NoAction
          } else {
            log.info(s"Re-requesting after ${PollDelay.recoveryDelay}")
            RetryActionAfter(GetInitialCrunchState(viewMode), PollDelay.recoveryDelay)
          }
      }
      .recoverWith {
        case throwable =>
          log.error(s"Call to crunch-state failed (${throwable.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetInitialCrunchState(viewMode), PollDelay.recoveryDelay))
      }
  }

  def getCrunchUpdatesAfterDelay(viewMode: ViewMode): Effect = Effect(Future(GetCrunchStateUpdates(viewMode))).after(crunchUpdatesRequestFrequency)
}
