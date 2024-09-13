package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, ModelRW}

case class SetMinStaffSuccessBanner(showBanner: Boolean) extends Action

case class CloseMinStaffSuccessBanner() extends Action

case class ShouldShowMinStaffSuccessBanner() extends Action

class MinStaffSuccessBannerHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {
  override protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case ShouldShowMinStaffSuccessBanner() =>
      val currentShowMinStaffSuccessBanner = modelRW.value.getOrElse(false)
      updated(Ready(currentShowMinStaffSuccessBanner))

    case SetMinStaffSuccessBanner(status) =>
      updated(Ready(status))

    case CloseMinStaffSuccessBanner() =>
      updated(Ready(false))
  }
}
