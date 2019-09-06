package drt.client.services.handlers

import diode._
import diode.data._
import diode.Implicits.runAfterImpl
import drt.client.actions.Actions._
import drt.client.logger._
import drt.client.services._
import drt.shared._
import drt.shared.CrunchApi._
import org.scalajs.dom
import upickle.default.read

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class PortStateUpdatesHandler[M](getCurrentViewMode: () => ViewMode,
                                 modelRW: ModelRW[M, (Pot[PortState], MillisSinceEpoch)]) extends LoggingActionHandler(modelRW) {
  val liveRequestFrequency: FiniteDuration = 2 seconds
  val forecastRequestFrequency: FiniteDuration = 15 seconds

  val thirtySixHoursInMillis: Long = 1000L * 60 * 60 * 36

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetPortStateUpdates(viewMode) =>
      val (_, lastUpdateMillis) = modelRW.value
      val startMillis = viewMode.dayStart.millisSinceEpoch
      val endMillis = startMillis + thirtySixHoursInMillis
      val updateRequestFuture = DrtApi.get(s"crunch?start=$startMillis&end=$endMillis&since=$lastUpdateMillis")

      val eventualAction = processUpdatesRequest(viewMode, updateRequestFuture)

      effectOnly(Effect(eventualAction))

    case UpdatePortStateFromUpdates(viewMode, _) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring out of date view response")
      noChange

    case UpdatePortStateFromUpdates(viewMode, crunchUpdates) =>
      modelRW.value match {
        case (Ready(existingState), _) =>
          val newState = updateStateFromUpdates(viewMode.dayStart.millisSinceEpoch, crunchUpdates, existingState)
          val scheduledUpdateRequest = Effect(Future(SchedulePortStateUpdateRequest(viewMode)))
          val newOriginCodes = crunchUpdates.flights.map(_.apiFlight.Origin) -- existingState.flights.map { case (_, fws) => fws.apiFlight.Origin }
          val effects = if (newOriginCodes.nonEmpty) scheduledUpdateRequest + Effect(Future(GetAirportInfos(newOriginCodes))) else scheduledUpdateRequest

          updated((Ready(newState), crunchUpdates.latest), effects)

        case _ =>
          log.warn(s"No existing state to apply updates to. Ignoring")
          noChange
      }

    case SchedulePortStateUpdateRequest(viewMode) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring out of date schedule updates request")
      noChange

    case SchedulePortStateUpdateRequest(viewMode) => effectOnly(getCrunchUpdatesAfterDelay(viewMode))
  }

  def processUpdatesRequest(viewMode: ViewMode, call: Future[dom.XMLHttpRequest]): Future[Action] = {
    call
      .map(r => read[Either[PortStateError, Option[PortStateUpdates]]](r.responseText))
      .map {
        case Right(Some(cu)) => UpdatePortStateFromUpdates(viewMode, cu)
        case Right(None) => SchedulePortStateUpdateRequest(viewMode)
        case Left(error) =>
          log.error(s"Failed to GetPortStateUpdates ${error.message}.")

          if (viewMode.isDifferentTo(getCurrentViewMode())) {
            log.info(s"The view appears to have changed. No need to retry")
            NoAction
          } else {
            log.info(s"Re-requesting after ${PollDelay.recoveryDelay}")
            RetryActionAfter(GetPortStateUpdates(viewMode), PollDelay.recoveryDelay)
          }
      }
      .recoverWith {
        case throwable =>
          log.error(s"Call to crunch-state failed (${throwable.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetPortStateUpdates(viewMode), PollDelay.recoveryDelay))
      }
  }

  def updateStateFromUpdates(startMillis: MillisSinceEpoch, crunchUpdates: PortStateUpdates, existingState: PortState): PortState = {
    val flights = updateAndTrimFlights(crunchUpdates, existingState, startMillis)
    val minutes = updateAndTrimCrunch(crunchUpdates, existingState, startMillis)
    val staff = updateAndTrimStaff(crunchUpdates, existingState, startMillis)
    PortState(flights, minutes, staff)
  }

  def updateAndTrimCrunch(crunchUpdates: PortStateUpdates, existingState: PortState, keepFromMillis: MillisSinceEpoch): SortedMap[TQM, CrunchApi.CrunchMinute] = {
    val relevantMinutes = existingState.crunchMinutes.dropWhile {
      case (TQM(_, _, m), _) => m < keepFromMillis
    }
    crunchUpdates.minutes.foldLeft(relevantMinutes) {
      case (soFar, newCm) => soFar.updated(TQM(newCm), newCm)
    }
  }

  def updateAndTrimStaff(crunchUpdates: PortStateUpdates, existingState: PortState, keepFromMillis: MillisSinceEpoch): SortedMap[TM, CrunchApi.StaffMinute] = {
    val relevantMinutes = existingState.staffMinutes.dropWhile {
      case (TM(_, m), _) => m < keepFromMillis
    }
    crunchUpdates.staff.foldLeft(relevantMinutes) {
      case (soFar, newSm) => soFar.updated(TM(newSm), newSm)
    }
  }

  def updateAndTrimFlights(crunchUpdates: PortStateUpdates, existingState: PortState, keepFromMillis: MillisSinceEpoch): SortedMap[UniqueArrival, ApiFlightWithSplits] = {
    val thirtyMinutesMillis = 30 * 60000
    val relevantFlights = existingState.flights
      .filter { case (_, fws) => fws.apiFlight.PcpTime.isDefined }
      .filter { case (_, fws) => keepFromMillis - thirtyMinutesMillis <= fws.apiFlight.PcpTime.getOrElse(0L) }

    crunchUpdates.flights.foldLeft(relevantFlights) {
      case (soFar, newFlight) =>
        val withoutOldFlight = soFar.filterNot { case (_, fws) => fws.apiFlight.uniqueId == newFlight.apiFlight.uniqueId }
        withoutOldFlight.updated(newFlight.apiFlight.unique, newFlight)
    }
  }

  def getCrunchUpdatesAfterDelay(viewMode: ViewMode): Effect = Effect(Future(GetPortStateUpdates(viewMode))).after(requestFrequency(viewMode))

  def requestFrequency(viewMode: ViewMode): FiniteDuration = viewMode match {
    case ViewLive => liveRequestFrequency
    case _ => forecastRequestFrequency
  }
}
