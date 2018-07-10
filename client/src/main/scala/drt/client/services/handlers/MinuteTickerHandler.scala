package drt.client.services.handlers

import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{RetryActionAfter, UpdateMinuteTicker}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.PollDelay

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MinuteTickerHandler[M](modelRW: ModelRW[M, Int]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case UpdateMinuteTicker =>
      val currentMinutes = SDate.now().getMinutes()

      val pollEffect = Effect(Future(RetryActionAfter(UpdateMinuteTicker, PollDelay.minuteUpdateDelay)))
      if (currentMinutes != value)
        updated(currentMinutes, pollEffect)
      else
        effectOnly(pollEffect)
  }
}
