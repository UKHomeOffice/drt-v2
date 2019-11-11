package test.roles

import controllers.UserRoleProviderLike
import drt.shared.{Role, Roles}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}


object MockRoles {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(session: Session): Set[Role] = {

    val maybeRoles = session.data.get("mock-roles")
    val mockRoles = maybeRoles.map(_.split(",").toSet.flatMap(Roles.parse)).getOrElse(Set.empty)
    mockRoles
  }

  object MockRolesProtocol extends DefaultJsonProtocol {
    implicit val mockRoleConverters: RootJsonFormat[MockRoles] = jsonFormat1((v: JsValue) => {
      val roles = MockRoles(v.convertTo[Set[String]].flatMap(Roles.parse))
      roles
    })
  }
}

case class MockRoles(roles: Set[Role])

object TestUserRoleProvider extends UserRoleProviderLike {
  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = {
    MockRoles(session) ++ userRolesFromHeader(headers)
  }
}
