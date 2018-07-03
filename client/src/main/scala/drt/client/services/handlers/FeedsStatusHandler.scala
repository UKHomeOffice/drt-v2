package drt.client.services.handlers

import autowire._
import boopickle.Default._
import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{AjaxClient, PollDelay}
import drt.shared._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetFeedStatuses() extends Action
case class SetFeedStatuses(statuses: Seq[FeedStatuses]) extends Action


class FeedsStatusHandler[M](modelRW: ModelRW[M, Pot[Seq[FeedStatuses]]]) extends LoggingActionHandler(modelRW) {
  implicit val pickler = compositePickler[FeedStatus].
    addConcreteType[FeedStatusSuccess].
    addConcreteType[FeedStatusFailure]

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case SetFeedStatuses(statuses) =>
      val scheduledRequest = Effect(Future(GetFeedStatuses())).after(15 seconds)

      log.info(s"setting feed status: $statuses")

      updated(Ready(statuses), scheduledRequest)

    case GetFeedStatuses() =>
      log.info(s"Calling getFeedStatuses")

      val apiCallEffect = Effect(AjaxClient[Api].getFeedStatuses().call()
        .map(SetFeedStatuses)
        .recoverWith {
          case _ =>
            log.error(s"Failed to get feed statuses. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeedStatuses(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
