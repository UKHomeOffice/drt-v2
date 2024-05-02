package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class SetSelectedTimeInterval(previousInterval: Int) extends Action

case class GetSelectedTimeInterval() extends Action

class UserSelectionPlanningPeriodHandler[M](modelRW: ModelRW[M, Pot[Int]]) extends LoggingActionHandler(modelRW) {

  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetSelectedTimeInterval() =>
      val apiCallEffect = Effect(DrtApi.get("data/user-selected-time-period")
        .map(r => SetSelectedTimeInterval(r.responseText match {
          case "15" => 15
          case _ => 60
        }))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Previous Time PeriodSelected data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetSelectedTimeInterval(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)

    case SetSelectedTimeInterval(previousPeriodInterval) => updated(Ready(previousPeriodInterval))

  }
}
