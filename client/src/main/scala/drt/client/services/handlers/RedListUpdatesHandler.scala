package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data._
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class RedListUpdatesHandler[M](modelRW: ModelRW[M, Pot[RedListUpdates]]) extends LoggingActionHandler(modelRW) {
  val requestFrequency: FiniteDuration = 60 seconds

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetRedListUpdates =>
      effectOnly(Effect(DrtApi.get("red-list/updates-legacy").map(r => {
        SetRedListUpdates(read[RedListUpdates](r.responseText))
      }).recoverWith {
        case e: Throwable =>
          log.warn(s"Red List updates request failed. Re-requesting after ${PollDelay.recoveryDelay}: ${e.getMessage}")
          Future(RetryActionAfter(GetRedListUpdates, PollDelay.recoveryDelay))
      }))

    case SetRedListUpdates(updates) =>
      val effect = Effect(Future(GetRedListUpdates)).after(requestFrequency)
      val pot = Ready(updates)
      if (modelRW.value.headOption == Option(updates)) {
        effectOnly(effect)
      } else {
        updated(pot, effect)
      }

    case SaveRedListUpdate(updateToSave) =>
      val responseFuture = DrtApi.post("red-list/updates", write(updateToSave))
        .map(_ => DoNothing())
        .recoverWith {
          case _ =>
            log.error(s"Failed to save Alert. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveRedListUpdate(updateToSave), PollDelay.recoveryDelay))
        }

      val updatedPot: Pot[RedListUpdates] = value.map(updates => updates.update(updateToSave))

      updated(updatedPot, Effect(responseFuture))

    case DeleteRedListUpdate(effectiveFrom: MillisSinceEpoch) =>
      val responseFuture = DrtApi.delete(s"red-list/updates/$effectiveFrom")
        .map(_ => DoNothing())
        .recoverWith {
          case _ =>
            log.error(s"Failed to delete red list update. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(DeleteRedListUpdate(effectiveFrom), PollDelay.recoveryDelay))
        }

      val updatedPot: Pot[RedListUpdates] = value.map(updates => updates.remove(effectiveFrom))

      updated(updatedPot, Effect(responseFuture))
  }
}
