package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.actions.Actions._
import drt.client.services.{DrtApi, PollDelay}
import drt.shared.PortCode
import upickle.default.read

import scala.collection.immutable.HashSet
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class RedListPortsHandler[M](modelRW: ModelRW[M, Pot[HashSet[PortCode]]]) extends LoggingActionHandler(modelRW) {
  val requestFrequency: FiniteDuration = 60.seconds

  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetRedListPorts(date) =>
      effectOnly(Effect(DrtApi.get(s"red-list/ports/${date.toISOString}")
        .map { response =>
          val redListCodes = read[HashSet[PortCode]](response.responseText)
          UpdateRedListPorts(redListCodes, date)
        }
        .recoverWith {
          case _ => Future(RetryActionAfter(GetRedListPorts(date), PollDelay.recoveryDelay))
        }))

    case UpdateRedListPorts(infos, date) =>
      val effect = Effect(Future(GetRedListPorts(date))).after(requestFrequency)
      updated(Ready(infos), effect)
  }
}
