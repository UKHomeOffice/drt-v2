package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data._
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.egates.{DeleteEgateBanksUpdates, PortEgateBanksUpdates}
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class EgateBanksUpdatesHandler[M](modelRW: ModelRW[M, Pot[PortEgateBanksUpdates]]) extends PotActionHandler(modelRW) {
  val requestFrequency: FiniteDuration = 60 seconds

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetPortEgateBanksUpdates =>
      effectOnly(Effect(DrtApi.get("egate-banks/updates")
        .map(r => SetEgateBanksUpdates(read[PortEgateBanksUpdates](r.responseText)))
        .recoverWith {
          case e: Throwable =>
            log.warn(s"Egates banks request failed. Re-requesting after ${PollDelay.recoveryDelay}: ${e.getMessage}")
            Future(RetryActionAfter(GetPortEgateBanksUpdates, PollDelay.recoveryDelay))
        }))

    case SetEgateBanksUpdates(updates) =>
      val effect = Effect(Future(GetPortEgateBanksUpdates)).after(requestFrequency)
      updateIfChanged(updates, effect)

    case SaveEgateBanksUpdate(updateToSave) =>
      val responseFuture = DrtApi.post("egate-banks/updates", write(updateToSave))
        .map(_ => DoNothing())
        .recoverWith {
          case _ =>
            log.error(s"Failed to save egate banks update. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveEgateBanksUpdate(updateToSave), PollDelay.recoveryDelay))
        }

      val updatedPot = value.map(updates => updates.update(updateToSave))

      updated(updatedPot, Effect(responseFuture))

    case DeleteEgateBanksUpdate(terminal, effectiveFrom) =>
      val responseFuture = DrtApi.delete(s"egate-banks/updates/${terminal.toString}/$effectiveFrom")
        .map(_ => DoNothing())
        .recoverWith {
          case _ =>
            log.error(s"Failed to delete egate banks update. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(DeleteEgateBanksUpdate(terminal, effectiveFrom), PollDelay.recoveryDelay))
        }

      val updatedPot = value.map(updates => updates.remove(DeleteEgateBanksUpdates(terminal, effectiveFrom)))

      updated(updatedPot, Effect(responseFuture))
  }
}
