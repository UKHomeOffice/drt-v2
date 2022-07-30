package drt.client.services.handlers

import boopickle.CompositePickler
import boopickle.Default._
import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared._
import uk.gov.homeoffice.drt.ports.FeedSource
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetFeedSourceStatuses() extends Action

case class SetFeedSourceStatuses(statuses: Seq[FeedSourceStatuses]) extends Action

case class CheckFeed(feedSource: FeedSource) extends Action

class FeedsHandler[M](modelRW: ModelRW[M, Pot[Seq[FeedSourceStatuses]]]) extends LoggingActionHandler(modelRW) {
  implicit val pickler: CompositePickler[FeedStatus] = compositePickler[FeedStatus].
    addConcreteType[FeedStatusSuccess].
    addConcreteType[FeedStatusFailure]

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case CheckFeed(feedSource) =>
      DrtApi.post("feeds/check", write(feedSource))
      noChange

    case SetFeedSourceStatuses(statuses) =>
      val scheduledRequest = Effect(Future(GetFeedSourceStatuses())).after(15 seconds)
      updated(Ready(statuses), scheduledRequest)

    case GetFeedSourceStatuses() =>
      log.info(s"Calling getFeedStatuses")

      val apiCallEffect = Effect(DrtApi.get("feeds/statuses")
        .map(r => SetFeedSourceStatuses(read[Seq[FeedSourceStatuses]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get feed statuses. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeedSourceStatuses(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
