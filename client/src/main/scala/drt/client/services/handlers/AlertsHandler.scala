package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data._
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.Alert
import drt.shared.CrunchApi.MillisSinceEpoch
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AlertsHandler[M](modelRW: ModelRW[M, Pot[List[Alert]]]) extends PotActionHandler(modelRW) {

  val alertsRequestFrequency: FiniteDuration = 10 seconds

  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetAlerts(since: MillisSinceEpoch) =>
      effectOnly(Effect(DrtApi.get(s"alerts/$since")
        .map { r =>
          val alerts = read[List[Alert]](r.responseText)
          SetAlerts(alerts, since)
        }
        .recoverWith {
          case _ =>
            log.info(s"Alerts request failed. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetAlerts(since), PollDelay.recoveryDelay))
        }))

    case SetAlerts(alerts, since) =>
      val poll = Effect(Future(GetAlerts(since))).after(alertsRequestFrequency)
      updateIfChanged(alerts, poll)

    case DeleteAllAlerts =>
      val responseFuture = DrtApi.delete("alerts")
        .map(_ => DoNothing())
        .recoverWith {
          case _ =>
            log.error(s"Failed to delete all alerts. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(DeleteAllAlerts, PollDelay.recoveryDelay))
        }
      updated(Empty, Effect(responseFuture))

    case SaveAlert(alert) =>
      val responseFuture = DrtApi.post("alerts", write(alert))
        .map(_ => DoNothing())
        .recoverWith {
          case _ =>
            log.error(s"Failed to save Alert. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(SaveAlert(alert), PollDelay.recoveryDelay))
        }

      val pot = value match {
        case Ready(alerts) => Ready(alert :: alerts)
        case _ => Ready(List(alert))
      }

      updated(pot, Effect(responseFuture))
  }
}
