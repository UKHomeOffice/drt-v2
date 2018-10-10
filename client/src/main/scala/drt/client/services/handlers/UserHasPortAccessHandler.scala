package drt.client.services.handlers

import boopickle.Default._
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.SPAMain
import drt.client.actions.Actions._
import drt.client.services.PollDelay
import org.scalajs.dom
import org.scalajs.dom.XMLHttpRequest
import org.scalajs.dom.ext.AjaxException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class UserHasPortAccessHandler[M](modelRW: ModelRW[M, Pot[Boolean]]) extends LoggingActionHandler(modelRW) {

  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetUserHasPortAccess =>

      val url = SPAMain.pathToThisApp + "/data/user/has-port-access"

      val eventualRequest: Future[XMLHttpRequest] = dom.ext.Ajax.get(url = url)
      effectOnly(Effect(eventualRequest.map(r => {
        SetUserHasPortAccess(r.status == 200)
      }).recover {
        case e: AjaxException if e.xhr.status == 401 =>
          SetUserHasPortAccess(false)
        case _ => RetryActionAfter(GetUserHasPortAccess, PollDelay.recoveryDelay)
      }))

    case SetUserHasPortAccess(hasAccess) =>
      updated(Ready(hasAccess))

  }
}
