package drt.client.services.handlers

import diode._
import diode.data.Pot
import drt.client.actions.Actions._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{ViewDay, ViewLive, ViewMode}
import drt.shared.CrunchApi.{PortState, MillisSinceEpoch}
import drt.shared.SDateLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ViewModeHandler[M](viewModePortStateMP: ModelRW[M, (ViewMode, Pot[PortState], MillisSinceEpoch)], portStateMP: ModelR[M, Pot[PortState]]) extends LoggingActionHandler(viewModePortStateMP) {

  def midnightThisMorning: SDateLike = SDate.midnightOf(SDate.now())

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewMode(newViewMode) =>
      val (currentViewMode, _, _) = value

      (newViewMode, currentViewMode) match {
        case (newVm, oldVm) if newVm.uUID != oldVm.uUID =>
          updated((newViewMode, Pot.empty[PortState], 0L), initialRequests(newViewMode))
        case _ =>
          noChange
      }
  }

  def initialRequests(newViewMode: ViewMode): EffectSet = {
    Effect(Future(GetInitialPortState(newViewMode))) +
      Effect(Future(GetStaffMovements(newViewMode))) +
      Effect(Future(GetShifts(newViewMode))) +
      Effect(Future(GetFixedPoints(newViewMode)))
  }
}
