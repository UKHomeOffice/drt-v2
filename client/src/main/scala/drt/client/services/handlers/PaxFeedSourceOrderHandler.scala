package drt.client.services.handlers

import diode.AnyAction.aType
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{GetPaxFeedSourceOrder, RetryActionAfter, UpdateGetPaxFeedSourceOrder}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.ports.FeedSource
import upickle.default.read

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class PaxFeedSourceOrderHandler[M](modelRW: ModelRW[M, List[FeedSource]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetPaxFeedSourceOrder =>
      updated(List(), Effect(DrtApi.get("pax-feed-source-order")
        .map(r => UpdateGetPaxFeedSourceOrder(read[List[FeedSource]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"GetPaxFeedSourceOrder request failed. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetPaxFeedSourceOrder, PollDelay.recoveryDelay))
        }))

    case UpdateGetPaxFeedSourceOrder(paxFeedSources) =>
      updated(paxFeedSources)
  }
}
