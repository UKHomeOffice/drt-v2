package drt.client.services.handlers

import boopickle.Default._
import diode.{ActionResult, Effect, ModelRW}
import drt.client.SPAMain
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.client.services.{PollDelay, RootModel}
import drt.shared.ShouldReload
import org.scalajs.dom
import ujson.Js.Value
import upickle.Js
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ShouldReloadHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {

  implicit val shouldReloadReadWriter: ReadWriter[ShouldReload] =
    readwriter[Js.Value].bimap[ShouldReload](reload => {
      s"""| {
          |  shouldReload: ${write(reload.shouldReload)}
          | }
      """.stripMargin

    }, (s: Value) => {
      ShouldReload(s("reload").bool)
    })

  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetShouldReload =>
      val url = SPAMain.pathToThisApp + "/data/should-reload"

      effectOnly(Effect(dom.ext.Ajax.get(url = url).map(r => {
        val reload = read[ShouldReload](r.responseText)
        if (reload.shouldReload) {
          log.info(s"triggering reload")
          TriggerReload
        } else {
          RetryActionAfter(GetShouldReload, PollDelay.recoveryDelay)
        }
      }).recoverWith {
        case _ =>
          Future(RetryActionAfter(GetShouldReload, PollDelay.recoveryDelay))
      }))
  }
}
