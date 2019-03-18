package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{PollDelay, ViewDay, ViewMode, ViewPointInTime}
import drt.shared.SDateLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LivePollingHandler[M](viewModeMP: ModelRW[M, ViewMode]) extends LoggingActionHandler(viewModeMP) {

  def midnightThisMorning: SDateLike = SDate.midnightOf(SDate.now())

  def isViewModeAbleToPoll(viewMode: ViewMode): Boolean = viewMode match {
    case ViewPointInTime(_) => false
    case viewMode: ViewDay if viewMode.millis < midnightThisMorning.millisSinceEpoch => false
    case _ => true
  }

  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case PollForUpdates =>
      val pollingEffects = Effect(Future(PollForCrunchUpdates)) + Effect(Future(PollForStaffUpdates))
      effectOnly(pollingEffects)

    case PollForCrunchUpdates =>
      val effects = Effect(Future(PollForCrunchUpdates)).after(PollDelay.crunchUpdateDelay) + Effect(Future(GetCrunchState()))
      if (isViewModeAbleToPoll(value))
        effectOnly(effects)
      else
        noChange

    case PollForStaffUpdates =>
      val effects = Effect(Future(PollForStaffUpdates)).after(PollDelay.staffUpdateDelay) +
        Effect(Future(GetShifts())) + Effect(Future(GetStaffMovements())) + Effect(Future(GetFixedPoints()))
      if (isViewModeAbleToPoll(value))
        effectOnly(effects)
      else
        noChange
  }
}
