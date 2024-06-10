package drt.client.services.handlers

import diode._
import diode.data.Pot
import drt.client.actions.Actions._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.ViewMode
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.PortState
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ViewModeHandler[M](now: () => SDateLike,
                         viewModePortStateMP: ModelRW[M, (ViewMode, Pot[PortState], MillisSinceEpoch)]
                        ) extends LoggingActionHandler(viewModePortStateMP) {

  def midnightThisMorning: SDateLike = SDate.midnightOf(SDate.now())

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewMode(newViewMode) =>
      val (currentViewMode, _, _) = value

      (newViewMode, currentViewMode) match {
        case (newVm, oldVm) if newVm.uUID != oldVm.uUID || value._2.isEmpty =>
          updated((newViewMode, Pot.empty[PortState], 0L), initialRequests(currentViewMode, newViewMode))
        case _ =>
          noChange
      }
  }

  def initialRequests(currentViewMode: ViewMode, newViewMode: ViewMode): EffectSet = {
    val effects = Effect(Future(GetInitialPortState(newViewMode))) +
      Effect(Future(GetStaffMovements(newViewMode))) +
      Effect(Future(GetShifts(newViewMode))) +
      Effect(Future(GetFixedPoints(newViewMode))) +
      Effect(Future(GetManifestSummariesForDate(newViewMode.dayEnd.toUtcDate))) +
      Effect(Future(GetManifestSummariesForDate(newViewMode.dayEnd.addDays(-1).toUtcDate)))

    val isHistoricView = newViewMode.dayEnd < now().getLocalLastMidnight

    if (!isHistoricView)
      effects + Effect(Future(ClearForecastAccuracy))
    else if (newViewMode.dayStart != currentViewMode.dayStart)
      effects + Effect(Future(GetForecastAccuracy(newViewMode.dayStart.toLocalDate)))
    else
      effects
  }
}
