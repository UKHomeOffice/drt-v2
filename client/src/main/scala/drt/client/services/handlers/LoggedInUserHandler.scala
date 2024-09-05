package drt.client.services.handlers

import diode.data.{Pot, Ready}
import diode._
import drt.client.SPAMain
import drt.client.actions.Actions._
import drt.client.logger.log
import org.scalajs.dom
import org.scalajs.dom.XMLHttpRequest
import ujson.Value
import uk.gov.homeoffice.drt.auth.{LoggedInUser, Roles}
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class TrackUser() extends Action

class LoggedInUserHandler[M](modelRW: ModelRW[M, Pot[LoggedInUser]]) extends LoggingActionHandler(modelRW) {

  implicit val loggedInUserReadWriter: ReadWriter[LoggedInUser] =
    readwriter[Value].bimap[LoggedInUser](user => {
      s"""| {
          |  userName: ${user.userName},
          |  id: ${user.id},
          |  email: ${user.email},
          |  roles: ${write(user.roles.map(_.name))}
          | }
      """.stripMargin

    }, (s: Value) => {
      LoggedInUser(
        s("userName").toString(),
        s("id").toString(),
        s("email").toString(),
        s("roles").arr.map(r => Roles.parse(r.value.toString)).collect { case Some(r) => r }.toSet,
      )
    })

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetLoggedInUser =>
      val url = SPAMain.absoluteUrl("data/user")
      val eventualRequest: Future[XMLHttpRequest] = dom.ext.Ajax.get(url = url)
      effectOnly(Effect(eventualRequest.map(r => SetLoggedInUser(read[LoggedInUser](r.responseText)))))

    case SetLoggedInUser(loggedInUser) =>
      println(s"setting logged in user: $loggedInUser")
      updated(Ready(loggedInUser))

    case TrackUser() => {
      val url = SPAMain.absoluteUrl("data/track-user")
      val eventualRequest: Future[XMLHttpRequest] = dom.ext.Ajax.get(url = url)
      effectOnly(Effect(eventualRequest.map(_ => NoAction)))
    }
  }
}
