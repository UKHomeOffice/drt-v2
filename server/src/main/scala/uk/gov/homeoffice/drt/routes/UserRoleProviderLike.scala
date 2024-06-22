package uk.gov.homeoffice.drt.routes

import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import slickdb.UserTableLike
import uk.gov.homeoffice.drt.auth.{LoggedInUser, Roles}
import uk.gov.homeoffice.drt.auth.Roles.Role

trait UserRoleProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val userService: UserTableLike

  def userRolesFromHeader(headers: Headers): Set[Role] = headers.get("X-Forwarded-Groups").map(_.split(",")).map(_.map(_.split("role:").last))
      .map(_.flatMap(Roles.parse).toSet).getOrElse(Set.empty[Role])

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role]

  def getLoggedInUser(config: Configuration, headers: Headers, session: Session): LoggedInUser = {
    val roles: Set[Role] = getRoles(config, headers, session)
    val email = headers.get("X-Forwarded-Email").getOrElse("Unknown")

    LoggedInUser(
      email = email,
      userName = headers.get("X-Forwarded-Preferred-Username").getOrElse(email),
      id = headers.get("X-Forwarded-Preferred-Username").getOrElse(email),
      roles = roles)
  }
}
