package drt.client.services.handlers

import diode.data.{Empty, Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.{FeedSourceArrival, UniqueArrival}
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ArrivalSourcesHandler[M](modelRW: ModelRW[M, Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])]]) extends LoggingActionHandler(modelRW) {
  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetArrivalSources(ua: UniqueArrival) =>
      val endpoint = s"arrival/${ua.number}/${ua.terminal.toString}/${ua.scheduled}"
      val effect = Effect(DrtApi.get(endpoint)
        .map { response =>
          val arrivalSources = read[List[Option[FeedSourceArrival]]](response.responseText)
          UpdateArrivalSources(ua, arrivalSources)
        }
        .recoverWith {
          case _ => Future(RetryActionAfter(GetArrivalSources(ua), PollDelay.recoveryDelay))
        })
      updated(Option((ua, Pending())), effect)
    case UpdateArrivalSources(ua, ars) =>
      updated(Option((ua, Ready(ars))))
    case RemoveArrivalSources =>
      updated(None)
  }
}
