package drt.client.services.handlers

import boopickle.Default._
import diode.data.{Pot, Ready}
import diode.{ActionResult, Effect, ModelRW}
import drt.client.SPAMain
import drt.client.actions.Actions._
import drt.client.logger.log
import drt.shared.{LoggedInUser, Roles}
import org.scalajs.dom
import org.scalajs.dom.XMLHttpRequest
import ujson.Js.Value
import upickle.Js
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class LoggedInUserHandler[M](modelRW: ModelRW[M, Pot[LoggedInUser]]) extends LoggingActionHandler(modelRW) {

  implicit val fooReadWrite: ReadWriter[LoggedInUser] =
    readwriter[Js.Value].bimap[LoggedInUser](user => {
      s"""| {
          |  userName: ${user.userName},
          |  id: ${user.id},
          |  email: ${user.email},
          |  roles: ${write(user.roles.map(_.name))}
          | }
      """.stripMargin

    }, (s: Value) => {
      LoggedInUser(s("userName").toString(), s("id").toString(), s("email").toString(), s("roles").arr
        .map(r => Roles.parse(r.value.toString)).collect { case Some(r) => r }.toSet)
    })

  protected def handle: PartialFunction[Any, ActionResult[M]] = {

    case GetLoggedInUser =>
      log.info(s"Getting logged in user")
      val url = SPAMain.pathToThisApp + "/data/user"

      val eventualRequest: Future[XMLHttpRequest] = dom.ext.Ajax.get(url = url)
      effectOnly(Effect(eventualRequest.map(r => {

        val loggedInUser: LoggedInUser = read[LoggedInUser](r.responseText)

        SetLoggedInUser(loggedInUser)
      }
      )))

    case SetLoggedInUser(loggedInUser) =>
      updated(Ready(loggedInUser))

  }
}
