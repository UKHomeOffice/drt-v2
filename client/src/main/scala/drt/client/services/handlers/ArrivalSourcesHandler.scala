package drt.client.services.handlers

import diode.data.{Pending, Pot, Ready}
import diode.{Action, ActionResult, Effect, EffectSingle, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.FeedSourceArrival
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.time.SDateLike
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ArrivalSourcesHandler[M](modelRW: ModelRW[M, Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])]]) extends LoggingActionHandler(modelRW) {
  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetArrivalSources(ua) =>
      val endpoint = s"arrival/${ua.number}/${ua.terminal.toString}/${ua.scheduled}/${ua.origin}"
      updated(Option((ua, Pending())), effect(ua, endpoint))

    case GetArrivalSourcesForPointInTime(pointInTime: SDateLike, ua: UniqueArrival) =>
      val endpoint = s"arrival/${pointInTime.millisSinceEpoch}/${ua.number}/${ua.terminal.toString}/${ua.scheduled}/${ua.origin}"
      updated(Option((ua, Pending())), effect(ua, endpoint))

    case UpdateArrivalSources(ua, ars) =>
      updated(Option((ua, Ready(ars))))

    case RemoveArrivalSources =>
      updated(None)
  }

  private def effect(ua: UniqueArrival, endpoint: String): EffectSingle[Action] = {
    Effect(DrtApi.get(endpoint)
      .map { response =>
        val arrivalSources = read[List[Option[FeedSourceArrival]]](response.responseText)
        UpdateArrivalSources(ua, arrivalSources)
      }
      .recoverWith {
        case _ => Future(RetryActionAfter(GetArrivalSources(ua), PollDelay.recoveryDelay))
      })
  }
}
