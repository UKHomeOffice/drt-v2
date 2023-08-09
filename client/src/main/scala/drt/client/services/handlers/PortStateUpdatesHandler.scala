package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode._
import diode.data._
import drt.client.actions.Actions._
import drt.client.logger._
import drt.client.services._
import drt.shared.CrunchApi._
import drt.shared._
import drt.shared.api.FlightManifestSummary
import org.scalajs.dom
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, UniqueArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import upickle.default.read

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class PortStateUpdatesHandler[M](getCurrentViewMode: () => ViewMode,
                                 portStateModel: ModelRW[M, (Pot[PortState], MillisSinceEpoch)],
                                 manifestSummariesModel: ModelR[M, Map[ArrivalKey, FlightManifestSummary]],
                                 paxFeedSourceOrder: ModelR[M, List[FeedSource]],
                                ) extends LoggingActionHandler(portStateModel) {
  val liveRequestFrequency: FiniteDuration = 2 seconds
  val forecastRequestFrequency: FiniteDuration = 15 seconds

  val thirtySixHoursInMillis: Long = 1000L * 60 * 60 * 36

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetPortStateUpdates(viewMode) =>
      val (_, lastUpdateMillis) = portStateModel.value
      val startMillis = viewMode.dayStart.millisSinceEpoch
      val endMillis = startMillis + thirtySixHoursInMillis
      val updateRequestFuture = DrtApi.get(s"crunch?start=$startMillis&end=$endMillis&since=$lastUpdateMillis")

      val eventualAction = processUpdatesRequest(viewMode, updateRequestFuture)

      effectOnly(Effect(eventualAction))

    case UpdatePortStateFromUpdates(viewMode, _) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring out of date view response")
      noChange

    case UpdatePortStateFromUpdates(viewMode, crunchUpdates) =>
      portStateModel.value match {
        case (Ready(existingState), _) =>
          val newState = updateStateFromUpdates(viewMode.dayStart.millisSinceEpoch, crunchUpdates, existingState)
          val scheduledUpdateRequest = Effect(Future(SchedulePortStateUpdateRequest(viewMode)))

          val newOriginCodes = crunchUpdates.updatesAndRemovals.arrivalUpdates.flatMap(_._2.toUpdate.map(_._1.origin)).toSet

          val airportsRequest = if (newOriginCodes.nonEmpty)
            List(Effect(Future(GetAirportInfos(newOriginCodes))))
          else
            List.empty

          val manifests = manifestSummariesModel.value
          val manifestsToFetch = crunchUpdates.updatesAndRemovals
            .splitsUpdates
            .flatMap(_._2.splits.filter(_._2.exists(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages)))
            .keys
            .map(ua => ArrivalKey(ua.origin, VoyageNumber(ua.number),ua.scheduled))
            .toSet

          val manifestRequest = if (manifestsToFetch.nonEmpty) {
            List(Effect(Future(GetManifestSummaries(manifestsToFetch))))
          } else List.empty

          val effects = (manifestRequest ++ airportsRequest)
            .foldLeft(new EffectSet(scheduledUpdateRequest, Set(), queue))(_ + _)

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
      .map(r => read[Option[PortStateUpdates]](r.responseText))
      .map {
        case Some(cu) => UpdatePortStateFromUpdates(viewMode, cu)
        case None => SchedulePortStateUpdateRequest(viewMode)
      }
      .recoverWith {
        case throwable =>
          log.error(s"Call to crunch-state failed (${throwable.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetPortStateUpdates(viewMode), PollDelay.recoveryDelay))
      }
  }

  def updateStateFromUpdates(startMillis: MillisSinceEpoch, crunchUpdates: PortStateUpdates, existingState: PortState): PortState = {
    val withArrivalUpdates = crunchUpdates.updatesAndRemovals.arrivalUpdates.foldLeft(FlightsWithSplits(existingState.flights)) {
      case (acc, (ts, diff)) =>
        diff.applyTo(acc, ts, paxFeedSourceOrder.value)._1
    }
    val withSplitsUpdates = crunchUpdates.updatesAndRemovals.splitsUpdates.foldLeft(withArrivalUpdates) {
      case (acc, (ts, diff)) =>
        diff.applyTo(acc, ts, paxFeedSourceOrder.value)._1
    }
    val trimmedFlights = trimFlights(withSplitsUpdates.flights, startMillis)

    val minutes = updateAndTrimCrunch(crunchUpdates, existingState, startMillis)
    val staff = updateAndTrimStaff(crunchUpdates, existingState, startMillis)
    PortState(trimmedFlights, minutes, staff)
  }

  def updateAndTrimCrunch(crunchUpdates: PortStateUpdates, existingState: PortState, keepFromMillis: MillisSinceEpoch): SortedMap[TQM, CrunchApi.CrunchMinute] = {
    val relevantMinutes = existingState.crunchMinutes.dropWhile {
      case (TQM(_, _, m), _) => m < keepFromMillis
    }
    crunchUpdates.queueMinutes.foldLeft(relevantMinutes) {
      case (soFar, newCm) => soFar.updated(TQM(newCm), newCm)
    }
  }

  def updateAndTrimStaff(crunchUpdates: PortStateUpdates, existingState: PortState, keepFromMillis: MillisSinceEpoch): SortedMap[TM, CrunchApi.StaffMinute] = {
    val relevantMinutes = existingState.staffMinutes.dropWhile {
      case (TM(_, m), _) => m < keepFromMillis
    }
    crunchUpdates.staffMinutes.foldLeft(relevantMinutes) {
      case (soFar, newSm) => soFar.updated(TM(newSm), newSm)
    }
  }

  def trimFlights(flights: Map[UniqueArrival, ApiFlightWithSplits], keepFromMillis: MillisSinceEpoch): Map[UniqueArrival, ApiFlightWithSplits] = {
    val thirtyMinutesMillis = 30 * 60000
    flights
      .filter { case (_, fws) => fws.apiFlight.PcpTime.isDefined }
      .filter { case (_, fws) => keepFromMillis - thirtyMinutesMillis <= fws.apiFlight.PcpTime.getOrElse(0L) }
  }

  def getCrunchUpdatesAfterDelay(viewMode: ViewMode): Effect = Effect(Future(GetPortStateUpdates(viewMode))).after(requestFrequency(viewMode))

  def requestFrequency(viewMode: ViewMode): FiniteDuration = viewMode match {
    case ViewLive => liveRequestFrequency
    case _ => forecastRequestFrequency
  }
}
