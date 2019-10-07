package controllers.application

import controllers.Application
import drt.shared.{LoggedInUser, Role}
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.mvc.{Action, AnyContent, Result}

import scala.concurrent.ExecutionContext.Implicits.global
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
    val preventAccess = !loggedInUser.hasRole(allowedRole) && enableRoleBasedAccessRestrictions

    if (!preventAccess) {
      auth(action)(request)
    } else {
      log.warning("Unauthorized")
      Future(unauthorizedMessageJson(allowedRole))
    }
  }

  private def unauthorizedMessageJson(allowedRole: Role): Result = {
    Unauthorized(
      s"""
         |{
         |  message: "Permission denied, you need $allowedRole to access this resource"
         |}
         """.stripMargin)
  }

  def auth[A](action: Action[A]): Action[A] = Action.async(action.parser) { request =>

    val loggedInUser: LoggedInUser = ctrl.getLoggedInUser(config, request.headers, request.session)
    val allowedRole = airportConfig.role

    val enablePortAccessRestrictions =
      config.getOptional[Boolean]("feature-flags.port-access-restrictions").getOrElse(false)

    if (!loggedInUser.hasRole(allowedRole))
      log.warning(
        s"User missing port role: ${loggedInUser.email} is accessing ${airportConfig.portCode} " +
          s"and has ${loggedInUser.roles.mkString(", ")} (needs $allowedRole)"
      )

    val preventAccess = !loggedInUser.hasRole(allowedRole) && enablePortAccessRestrictions

    if (preventAccess) {
      Future(Unauthorized(
        s"""
           |{
           |  message: "Permission denied, you need $allowedRole to access this resource"
           |}
         """.stripMargin))
    } else {
      action(request)
    }
  }
}
