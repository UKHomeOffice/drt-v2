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

  def userRolesFromHeader(headers: Headers): Set[Role] = headers.get("X-Auth-Roles").map(_.split(",").flatMap(Roles.parse).toSet).getOrElse(Set.empty[Role])

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role]

  def getLoggedInUser(config: Configuration, headers: Headers, session: Session): LoggedInUser = {
    log.info(s"Getting logged in user headers=$headers   session=$session")
    val baseRoles = Set()
    val roles: Set[Role] =
      getRoles(config, headers, session) ++ baseRoles
    val email = headers.get("X-Auth-Email").getOrElse("Unknown")

    LoggedInUser(
      email = email,
      userName = headers.get("X-Auth-Username").getOrElse(email),
      id = headers.get("X-Auth-Userid").getOrElse(email),
      roles = roles)
  }
}
