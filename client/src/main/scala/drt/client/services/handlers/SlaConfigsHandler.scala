package drt.client.services.handlers

import diode.AnyAction.aType
import diode.data.{Pending, Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.{RemoveSlasUpdate, DoNothing, GetSlaConfigs, RetryActionAfter, SaveSlasUpdate, UpdateSlaConfigs}
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class SlaConfigsHandler[M](modelRW: ModelRW[M, Pot[SlaConfigs]]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetSlaConfigs =>
      updated(Pending(), Effect(DrtApi.get("sla-configs")
        .map(r => UpdateSlaConfigs(read[SlaConfigs](r.responseText))).recoverWith {
          case _ =>
            log.error(s"SlaConfigs request failed. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetSlaConfigs, PollDelay.recoveryDelay))
        }))

    case UpdateSlaConfigs(slaConfigs) =>
      updated(Ready(slaConfigs))

    case SaveSlasUpdate(update) =>
      val eventualUpdate = DrtApi.post("sla-configs", write(update))
        .map { _ =>
          value match {
            case Ready(configs) => UpdateSlaConfigs(configs.update(update))
            case _ => DoNothing()
          }
        }
        .recoverWith {
          case _ =>
            log.error(s"Failed to save sla config update. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveSlasUpdate(update), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(eventualUpdate))

    case RemoveSlasUpdate(effectiveFrom) =>
      val eventualUpdate = DrtApi.delete(s"sla-configs/$effectiveFrom")
        .map { _ =>
          value match {
            case Ready(configs) => UpdateSlaConfigs(configs.remove(effectiveFrom))
            case _ => DoNothing()
          }
        }
        .recoverWith {
          case _ =>
            log.error(s"Failed to remove sla config update. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(RemoveSlasUpdate(effectiveFrom), PollDelay.recoveryDelay))
        }
      effectOnly(Effect(eventualUpdate))
  }
}
