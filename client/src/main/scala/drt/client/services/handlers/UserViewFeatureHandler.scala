package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.services.DrtApi

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetUserViewFeatureCount() extends Action

case class SetUserViewFeatureCount(count: Int) extends Action

class UserViewFeatureHandler[M](modelRW: ModelRW[M, Pot[Int]]) extends LoggingActionHandler(modelRW) {

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetUserViewFeatureCount(count) =>
      updated(Ready(count))

    case GetUserViewFeatureCount() =>
      val apiCallEffect = Effect(DrtApi.get("viewed-video-count")
        .map(r => SetUserViewFeatureCount(r.responseText.toInt)))
      effectOnly(apiCallEffect)
  }
}
