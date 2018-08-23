package drt.client.services.handlers

import diode.data.{Pending, PendingStale, Pot}
import diode.{ActionResult, Effect, ModelR, ModelRW}
import drt.client.actions.Actions.{GetCrunchState, GetShifts, GetStaffMovements, SetViewMode}
import drt.client.logger.log
import drt.client.services.{ViewDay, ViewLive, ViewMode}
import drt.shared.CrunchApi.{CrunchState, MillisSinceEpoch}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ViewModeHandler[M](viewModeCrunchStateMP: ModelRW[M, (ViewMode, Pot[CrunchState], MillisSinceEpoch)], crunchStateMP: ModelR[M, Pot[CrunchState]]) extends LoggingActionHandler(viewModeCrunchStateMP) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewMode(newViewMode) =>
      val (currentViewMode, _, currentLatestUpdateMillis) = value

      val latestUpdateMillis = (newViewMode, currentViewMode) match {
        case (newVm, oldVm) if newVm != oldVm => 0L
        case (ViewDay(newTime), ViewDay(oldTime)) if newTime != oldTime => 0L
        case _ => currentLatestUpdateMillis
      }

      log.info(s"VM: Set client newViewMode from $currentViewMode to $newViewMode. latestUpdateMillis: $latestUpdateMillis")
      (currentViewMode, newViewMode, crunchStateMP.value) match {
        case (_, _, cs@Pending(_)) =>
          updated((newViewMode, cs, latestUpdateMillis))
        case (ViewLive(), nvm, PendingStale(_, _)) if nvm != ViewLive() =>
          updated((newViewMode, Pending(), latestUpdateMillis))
        case (_, _, cs@PendingStale(_, _)) =>
          updated((newViewMode, cs, latestUpdateMillis))
        case _ =>
          val effects = Effect(Future(GetCrunchState())) + Effect(Future(GetStaffMovements())) + Effect(Future(GetShifts()))
          updated((newViewMode, Pending(), latestUpdateMillis), effects)
      }
  }
}
