package test

import controllers.UserRoleProviderLike
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}

import scala.language.postfixOps

object MockRoles {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(session: Session): List[String] = {

    log.info(s"Session: $session")
    val maybeRoles = session.data.get("mock-roles")
    log.info(s"Maybe roles: $maybeRoles")
    val mockRoles = maybeRoles.map(_.split(",").toList).getOrElse(List())
    log.info(s"Using these mock roles: $mockRoles")
    mockRoles
  }

  object MockRolesProtocol extends DefaultJsonProtocol {
    implicit val mockRoleConverters: RootJsonFormat[MockRoles] = jsonFormat1((v: JsValue) => {
      log.info(s"Got this json $v")
      MockRoles(v.convertTo[List[String]])
    })
  }

}

case class MockRoles(roles: List[String])

object TestUserRoleProvider  {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def getRoles(config: Configuration, headers: Headers, session: Session): List[String] = {
    log.info(s"Using MockRoles with $session")
    MockRoles(session)
  }
}
