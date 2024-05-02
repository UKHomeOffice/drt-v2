package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class SetTimePeriodSelected(previousPeriod: Int) extends Action

case class GetTimePeriodSelected() extends Action

class UserStaffPlanningTimePeriodHandler[M](modelRW: ModelRW[M, Pot[Int]]) extends LoggingActionHandler(modelRW) {

  override
  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetTimePeriodSelected() =>
      val apiCallEffect = Effect(DrtApi.get("data/user-selected-time-period")
        .map(r => SetTimePeriodSelected(r.responseText match {
          case "15" => 15
          case _ => 60
        }))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get Previous Time PeriodSelected data. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetTimePeriodSelected(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)

    case SetTimePeriodSelected(previousPeriod) => updated(Ready(previousPeriod))

  }
}
