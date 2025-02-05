package controllers.application

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.util.Timeout
import drt.http.ProdSendAndReceive
import drt.shared.ErrorResponse
import drt.users.KeyCloakClient
import play.api.Configuration
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.JsResult.Exception
import play.api.libs.json.{JsError, JsObject, Json, Writes}
import play.api.mvc._
import slickdb.UserRow
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ManageUsers, Role}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.AirportConfig
import upickle.default.write

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}


abstract class AuthController(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AbstractController(cc) {

  val log: LoggingAdapter = ctrl.system.log

  implicit val ec: ExecutionContext = ctrl.ec

  implicit val config: Configuration = ctrl.config

  implicit val actorSystem: ActorSystem = ctrl.system

  val airportConfig: AirportConfig = ctrl.airportConfig

  implicit val timeout: Timeout = ctrl.timeout

  def getLoggedInUser: Action[AnyContent] = Action { request =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)
    implicit val userWrites: Writes[LoggedInUser] = new Writes[LoggedInUser] {
      def writes(user: LoggedInUser): JsObject = Json.obj(
        "userName" -> user.userName,
        "id" -> user.id,
        "email" -> user.email,
        "roles" -> user.roles.map(_.name),
      )
    }

    Ok(Json.toJson(user))
  }

  def trackUser: Action[AnyContent] = Action.async { request =>
    val loggedInUser = ctrl.getLoggedInUser(config, request.headers, request.session)
    ctrl.userService.upsertUser(
      UserRow(id = loggedInUser.id,
        username = loggedInUser.userName,
        email = loggedInUser.email,
        latest_login = new java.sql.Timestamp(ctrl.now().millisSinceEpoch),
        inactive_email_sent = None,
        revoked_access = None,
        drop_in_notification_at = None,
        created_at = Some(new java.sql.Timestamp(ctrl.now().millisSinceEpoch)),
        feedback_banner_closed_at = None,
        staff_planning_interval_minutes = None))
    Future.successful(Ok(s"User-tracked"))
  }

  def closeBanner: Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Forwarded-Email").getOrElse("Unknown")
    val result: Future[Int] = ctrl.userService.updateCloseBanner(email = userEmail, at = new Timestamp(ctrl.now().millisSinceEpoch))
    result.map(_ => Ok("Successfully closed banner"))
  }

  def keyCloakClientWithHeader(headers: Headers): KeyCloakClient with ProdSendAndReceive = {
    val token = headers.get("X-Forwarded-Access-Token")
      .getOrElse(throw Exception(JsError("X-Forwarded-Access-Token missing from headers, we need this to query the Key Cloak API.")))
    val keyCloakUrl = config.getOptional[String]("key-cloak.url")
      .getOrElse(throw Exception(JsError("Missing key-cloak.url config value, we need this to query the Key Cloak API")))
    new KeyCloakClient(token, keyCloakUrl) with ProdSendAndReceive
  }

  def userDetails(email: String): Action[AnyContent] = Action.async { request =>
    if (ctrl.getLoggedInUser(config, request.headers, request.session).roles.contains(ManageUsers)) {
      val keyCloakClient = keyCloakClientWithHeader(request.headers)
      keyCloakClient.getUsersForEmail(email) map {
        case Some(userDetails) => Ok(write(userDetails))
        case _ => throw Exception(JsError(s"unable to get user details for email $email"))
      }
    } else Future.successful(Unauthorized(write(ErrorResponse(s"Permission denied, do not have access"))))
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
          Future.failed(Exception(JsError(s"Unable to add $userId to $group")))
      }
    } else Future.successful(Unauthorized(write(ErrorResponse(s"Permission denied, do not have access"))))

  }


  def getUserHasPortAccess: Action[AnyContent] = auth {
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
    Forbidden(write(ErrorResponse(s"Permission denied, you need $allowedRole to access this resource")))

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
