package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import scala.concurrent.Future

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class SetHidePaxDataSource(hide: Boolean) extends Action

case class UpdateHidePaxDataSource(hide: Boolean) extends Action

//case class HidePaxDataSource() extends Action
//
//case class ShowPaxDataSource() extends Action

case class ShouldHidePaxDataSource() extends Action

class UserHidePaxDataSourceHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {
  override protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case ShouldHidePaxDataSource() =>
      val apiCallEffect = Effect(DrtApi.get("data/hide-pax-datasource-icon")
        .map(r => SetHidePaxDataSource(r.responseText == "true"))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get show pax data source. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(ShouldHidePaxDataSource(), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)

    case SetHidePaxDataSource(status) =>
      updated(Ready(status))

    case UpdateHidePaxDataSource(status) =>
      val apiCallEffect = Effect(DrtApi.post(s"data/hide-pax-datasource-icon/$status", "")
        .map(_ => SetHidePaxDataSource(status))
        .recoverWith {
          case _ =>
            log.error(s"Failed to update pax data source. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(UpdateHidePaxDataSource(status), PollDelay.recoveryDelay))
        })
      effectOnly(apiCallEffect)
  }
}
//    case HidePaxDataSource() =>
//      val apiCallEffect = Effect(DrtApi.post("data/hide-pax-datasource-icon/true", "")
//        .map(_ => SetHidePaxDataSource(true))
//        .recoverWith {
//          case _ =>
//            log.error(s"Failed to update hide data source with api. Re-requesting after ${PollDelay.recoveryDelay}")
//            Future(RetryActionAfter(HidePaxDataSource(), PollDelay.recoveryDelay))
//        })
//      effectOnly(apiCallEffect)
//  }
//}
