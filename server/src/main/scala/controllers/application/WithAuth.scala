package controllers.application

import controllers.Application
import drt.shared.ErrorResponse
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.mvc.{Action, AnyContent, Result}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.Role
import upickle.default.write

import scala.concurrent.Future


trait WithAuth {
  self: Application =>

  def getLoggedInUser(): Action[AnyContent] = Action { request =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)

    implicit val userWrites: Writes[LoggedInUser] = new Writes[LoggedInUser] {
      def writes(user: LoggedInUser): JsObject = Json.obj(
        "userName" -> user.userName,
        "id" -> user.id,
        "email" -> user.email,
        "roles" -> user.roles.map(_.name)
      )
    }

    Ok(Json.toJson(user))
  }

  def getUserHasPortAccess(): Action[AnyContent] = auth {
    Action {
      Ok("{userHasAccess: true}")
    }
  }

  def isLoggedIn: Action[AnyContent] = Action {
    Ok("{loggedIn: true}")
  }

  def authByRole[A](allowedRole: Role)(action: Action[A]): Action[A] = Action.async(action.parser) { request =>
    val loggedInUser: LoggedInUser = ctrl.getLoggedInUser(config, request.headers, request.session)
    log.debug(s"${loggedInUser.roles}, allowed role $allowedRole")
    val allowAccess = loggedInUser.hasRole(allowedRole)

    if (allowAccess) {
      auth(action)(request)
    } else {
      log.warning("Unauthorized")
      Future(unauthorizedMessageJson(allowedRole))
    }
  }

  def unauthorizedMessageJson(allowedRole: Role): Result =
    Unauthorized(write(ErrorResponse(s"Permission denied, you need $allowedRole to access this resource")))

  def auth[A](action: Action[A]): Action[A] = Action.async(action.parser) { request =>

    val loggedInUser: LoggedInUser = ctrl.getLoggedInUser(config, request.headers, request.session)
    val portRole = airportConfig.role

    val noPortAccess = !loggedInUser.hasRole(portRole)
    val noEnvironmentAccess = !loggedInUser.canAccessEnvironment(ctrl.env)

    val preventAccess = noPortAccess || noEnvironmentAccess

    if (preventAccess) {
      if (noPortAccess)
        log.warning(
          s"User missing port role: ${loggedInUser.email} is accessing ${airportConfig.portCode} " +
            s"and has ${loggedInUser.roles.mkString(", ")} (needs $portRole)")

      if (noEnvironmentAccess)
        log.warning(s"User is restricted to environments: ${loggedInUser.restrictToEnvironments}. This is ${ctrl.env}")

      Future(unauthorizedMessageJson(portRole))
    } else {
      action(request)
    }
  }
}
