package test.roles

import controllers.UserRoleProviderLike
import drt.shared.{Role, Roles}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}

import scala.language.postfixOps

object MockRoles {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(session: Session): Set[Role] = {

    log.info(s"Session: $session")
    val maybeRoles = session.data.get("mock-roles")
    log.info(s"Maybe roles: $maybeRoles")
    val mockRoles = maybeRoles.map(_.split(",").toSet.flatMap(Roles.parse)).getOrElse(Set.empty)
    log.info(s"Using these mock roles: $mockRoles")
    mockRoles
  }

  object MockRolesProtocol extends DefaultJsonProtocol {
    implicit val mockRoleConverters: RootJsonFormat[MockRoles] = jsonFormat1((v: JsValue) => {
      log.info(s"MR: Got this json $v")
      val roles = MockRoles(v.convertTo[Set[String]].flatMap(Roles.parse))
      log.info(s"MR: Got these roles from it: $roles")
      roles
    })
  }

}

case class MockRoles(roles: Set[Role])

object TestUserRoleProvider extends UserRoleProviderLike {

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = {
    log.info(s"Using MockRoles with $session and $headers")
    MockRoles(session) ++ userRolesFromHeader(headers)
  }
}
