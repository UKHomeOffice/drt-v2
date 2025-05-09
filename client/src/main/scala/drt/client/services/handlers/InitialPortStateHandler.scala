package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode._
import diode.data._
import drt.client.actions.Actions._
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi._
import drt.shared.PortState
import org.scalajs.dom
import uk.gov.homeoffice.drt.ports.PortCode
import upickle.default.read

import scala.collection.immutable.HashSet
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class InitialPortStateHandler[M](getCurrentViewMode: () => ViewMode,
                                 portStateModel: ModelRW[M, (Pot[PortState], MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch, Pot[HashSet[PortCode]])],
                                ) extends LoggingActionHandler(portStateModel) {
  private val crunchUpdatesRequestFrequency: FiniteDuration = 2 seconds

  val thirtySixHoursInMillis: Long = 1000L * 60 * 60 * 36

  def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetInitialPortState(viewMode) =>
      val startMillis = viewMode.dayStart.millisSinceEpoch
      val endMillis = startMillis + thirtySixHoursInMillis
      val updateRequestFuture = viewMode match {
        case ViewDay(_, Some(time)) => DrtApi.get(s"crunch-snapshot/${time.millisSinceEpoch}?start=$startMillis&end=$endMillis")
        case _ => DrtApi.get(s"crunch?start=$startMillis&end=$endMillis")
      }

      val eventualAction = processRequest(viewMode, updateRequestFuture)

      val effects = Effect(Future(ShowLoader())) + Effect(eventualAction)
      updated((value._1, 0L, 0L, 0L, value._5), effects)

    case SetPortState(viewMode, _) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring out of date view response")
      noChange

    case SetPortState(viewMode, portState) =>
      val originCodes = portState.flights
        .flatMap { case (_, fws) =>
          Set(fws.apiFlight.Origin, fws.apiFlight.PreviousPort.getOrElse(fws.apiFlight.Origin))
        }
        .toSet

      val hideLoader = Effect(Future(HideLoader()))
      val fetchOrigins = Effect(Future(GetAirportInfos(originCodes)))
      val fetchRedList = Effect(Future(GetRedListPorts(viewMode.dayEnd.toLocalDate)))

      val actions = hideLoader + fetchOrigins + fetchRedList

      val effects = if (getCurrentViewMode().isHistoric(SDate.now()))
        actions + Effect(Future(GetPassengerInfoForFlights))
      else {
        viewMode match {
          case ViewDay(_, None) => actions + getCrunchUpdatesAfterDelay(viewMode)
          case ViewLive => actions + getCrunchUpdatesAfterDelay(viewMode)
          case _ => actions
        }
      }

      updated((Ready(portState), portState.flightsLatest, portState.crunchMinutesLatest, portState.staffMinutesLatest, Pending()), effects)
  }

  private def processRequest(viewMode: ViewMode, call: Future[dom.XMLHttpRequest]): Future[Action] = {
    call
      .map(r => read[PortState](r.responseText))
      .map(portState => SetPortState(viewMode, portState))
      .recoverWith {
        case throwable =>
          log.error(s"Call to crunch-state failed (${throwable.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetInitialPortState(viewMode), PollDelay.recoveryDelay))
      }
  }

  def getCrunchUpdatesAfterDelay(viewMode: ViewMode): Effect =
    Effect(Future(GetPortStateUpdates(viewMode))).after(crunchUpdatesRequestFrequency)
}
