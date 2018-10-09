package drt.client.services.handlers

import diode.data.{Empty, Pending, PendingStale, Pot}
import diode.{ActionResult, Effect, ModelR, ModelRW}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{ViewDay, ViewLive, ViewMode}
import drt.shared.CrunchApi.{CrunchState, MillisSinceEpoch}
import drt.shared.SDateLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ViewModeHandler[M](viewModeCrunchStateMP: ModelRW[M, (ViewMode, Pot[CrunchState], MillisSinceEpoch)], crunchStateMP: ModelR[M, Pot[CrunchState]]) extends LoggingActionHandler(viewModeCrunchStateMP) {

  def midnightThisMorning: SDateLike = SDate.midnightOf(SDate.now())

  def isViewModeAbleToPoll(viewMode: ViewMode): Boolean =   viewMode match {
    case ViewLive() => true
    case ViewDay(time) if time.millisSinceEpoch >= midnightThisMorning.millisSinceEpoch => true
    case _ => false
  }

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetViewMode(newViewMode) =>
      val (currentViewMode, _, currentLatestUpdateMillis) = value

      val latestUpdateMillis = (newViewMode, currentViewMode) match {
        case (newVm, oldVm) if newVm != oldVm => 0L
        case (ViewDay(newTime), ViewDay(oldTime)) if newTime != oldTime => 0L
        case _ => currentLatestUpdateMillis
      }

      log.info(s"VM: Set client newViewMode from $currentViewMode to $newViewMode. latestUpdateMillis: $latestUpdateMillis, crunchStateMP: ${crunchStateMP.value.getClass.getSimpleName}")
      (currentViewMode, newViewMode, crunchStateMP.value) match {
        case (cv, nv, pendingStale@PendingStale(_, _)) if cv == nv && isViewModeAbleToPoll(cv) =>
          log.info("crunch: Setting to PS from PS for live or future")
          updated((newViewMode, pendingStale, latestUpdateMillis))
        case (_, nv, PendingStale(_, _)) if isViewModeAbleToPoll(nv)  =>
          log.info("crunch: Setting to Pending from PS")
          updated((newViewMode, Pending(), latestUpdateMillis))
        case (cv, nv, Empty) if cv != nv && isViewModeAbleToPoll(cv) && isViewModeAbleToPoll(nv)  =>
          log.info("crunch: Setting to Pending from Empty")
          updated((newViewMode, Pending(), latestUpdateMillis))
        case (cv, nv, Empty) if cv == nv && isViewModeAbleToPoll(cv) && latestUpdateMillis != 0L =>
          log.info("crunch: Setting to Pending from Empty for live or future")
          updated((newViewMode, Pending(), latestUpdateMillis))
        case _ =>
          log.info("crunch: will poll")
          val effects = Effect(Future(GetCrunchState())) + Effect(Future(GetStaffMovements())) + Effect(Future(GetShifts())) + Effect(Future(GetFixedPoints()))
          updated((newViewMode, Pending(), latestUpdateMillis), effects)
      }
  }
}
