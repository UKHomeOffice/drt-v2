package controllers.application

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpResponse
import controllers.Application
import drt.http.ProdSendAndReceive
import drt.shared.ErrorResponse
import drt.shared.KeyCloakApi.KeyCloakUser
import drt.users.KeyCloakClient
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.JsResult.Exception
import play.api.libs.json.{JsError, JsObject, Json, Writes}
import play.api.mvc.{Action, AnyContent, Headers, Result}
import spray.json.DefaultJsonProtocol.jsonFormat7
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ManageUsers, Role}
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

  def keyCloakClientWithHeader(headers: Headers): KeyCloakClient with ProdSendAndReceive = {
    val token = headers.get("X-Auth-Token")
      .getOrElse(throw new Exception(JsError("X-Auth-Token missing from headers, we need this to query the Key Cloak API.")))
    val keyCloakUrl = config.getOptional[String]("key-cloak.url")
      .getOrElse(throw new Exception(JsError("Missing key-cloak.url config value, we need this to query the Key Cloak API")))
    new KeyCloakClient(token, keyCloakUrl) with ProdSendAndReceive
  }

  def userDetails(email: String) = Action { request =>

    if (ctrl.getLoggedInUser(config, request.headers, request.session).roles.contains(ManageUsers)) {
      val keyCloakClient = keyCloakClientWithHeader(request.headers)
      keyCloakClient.getAllUsers().find(_.email == email) match {
        case Some(userDetails) => Ok(write(userDetails))
        case _ => throw Exception(JsError(s"unable to get userdetails for email $email"))
      }
    } else Unauthorized(write(ErrorResponse(s"Permission denied, do not have access")))
  }

  def addUserToGroup(userId: String, group: String): Action[AnyContent] = Action.async { request =>
    if (ctrl.getLoggedInUser(config, request.headers, request.session).roles.contains(ManageUsers)) {
      val keyCloakClient = keyCloakClientWithHeader(request.headers)
      val keyCloakGroup = keyCloakClient.getGroups.map(a => a.find(_.name == group))

      keyCloakGroup.flatMap {
        case Some(kcg) =>
          val response: Future[HttpResponse] = keyCloakClient.addUserToGroup(userId, kcg.id)
          response map { r =>
            r.status.intValue match {
              case s if s > 200 && s < 300 => log.info(s"Added group $group  to userId $userId , with response status: ${r.status}  $r")
                Ok(s"Added group $group to userId $userId")
              case _ => throw Exception(JsError(s"unable to add group $group to userId $userId response from keycloak $response"))
            }
          }

        case _ => log.error(s"Unable to add $userId to $group")
          Future.failed(new Exception(JsError(s"Unable to add $userId to $group")))
      }
    } else Future.successful(Unauthorized(write(ErrorResponse(s"Permission denied, do not have access"))))

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
    val allowedRole = airportConfig.role

    if (!loggedInUser.hasRole(allowedRole))
      log.warning(
        s"User missing port role: ${loggedInUser.email} is accessing ${airportConfig.portCode} " +
          s"and has ${loggedInUser.roles.mkString(", ")} (needs $allowedRole)"
      )

    val preventAccess = !loggedInUser.hasRole(allowedRole)

    if (preventAccess) {
      Future(unauthorizedMessageJson(allowedRole))
    } else {
      action(request)
    }
  }
}
