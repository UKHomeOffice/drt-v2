package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import drt.shared.{DropIn, DropInRegistration}
import email.GovNotifyEmail
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import slickdb.DropInRow
import uk.gov.homeoffice.drt.auth.Roles.BorderForceStaff
import upickle.default.write

import scala.concurrent.Future
import scala.util.Try


class DropInsController @Inject()(cc: ControllerComponents,
                                  ctrl: DrtSystemInterface,
                                  govNotifyEmail: GovNotifyEmail) extends AuthController(cc, ctrl) {

  lazy val govNotifyApiKey = config.get[String]("notifications.gov-notify-api-key")

  lazy val dropInRegistrationTemplateId = ctrl.config.get[String]("notifications.dropIn-registration-templateId")

  lazy val dropInRegistrationHostTemplateId = ctrl.config.get[String]("notifications.dropIn-registration-host-templateId")

  lazy val dropInHostEmail = config.get[String]("notifications.dropIn-host-email")

  import drt.shared.DropIn._
  import drt.shared.DropInRegistration._

  def dropIns(): Action[AnyContent] = Action.async { _ =>
    val dropInsJson: Future[Seq[DropIn]] = ctrl.dropInService.getFuturePublishedDropIns()
    dropInsJson.map(dropIns => Ok(write(dropIns)))
  }

  def getDropInRegistrations: Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
    val dropInsRegistrationJson: Future[Seq[DropInRegistration]] = ctrl.dropInRegistrationService
      .getDropInRegistrations(userEmail)
      .map(_.map(_.toDropInRegistration))
    dropInsRegistrationJson.map(registered => Ok(write(registered)))
  }

  def createDropInRegistration: Action[AnyContent] = authByRole(BorderForceStaff) {
    Action { implicit request =>
      import spray.json.DefaultJsonProtocol._
      import spray.json._
      val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
      request.body.asText match {
        case Some(content) =>
          Try(content.parseJson.convertTo[String])
            .map { id =>
              ctrl.dropInRegistrationService.createDropInRegistration(userEmail, id).map { _ =>
                ctrl.dropInService.getDropIns(Seq(id)).map(sendDropInRegistrationEmails(userEmail, _))
              }.recover {
                case e => log.warning(s"Error while db insert for drop-in registration", e)
                  BadRequest(s"Failed to register drop-ins for user $userEmail")
              }
              Ok("Successfully registered drop-ins")
            }.recover {
            case e => log.warning(s"Error while drop-in registration", e)
              BadRequest(s"Failed to register drop-ins for user $userEmail")
          }.getOrElse(BadRequest("Failed to parse json"))
        case None => BadRequest("No content")
      }
    }
  }


  private def sendDropInRegistrationEmails(email: String, dropIns: Seq[DropInRow]) = {
    def contactEmail: Option[String] = config.getOptional[String]("contact-email")

    dropIns.map { dropIn =>
      val personalisation = govNotifyEmail
        .dropInRegistrationConfirmation(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), email, dropIn)
      val hostEmailPersonalisation = govNotifyEmail
        .dropInRegistrationHost(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), dropInHostEmail, email, dropIn)
      val govNotifyReference = config.get[String]("notifications.reference")

      govNotifyEmail.sendRequest(govNotifyReference,
        email,
        dropInRegistrationTemplateId,
        personalisation).recover {
        case e => log.error(s"Error sending drop-in registration email to user $email", e)
      }

      govNotifyEmail.sendRequest(govNotifyReference,
        dropInHostEmail,
        dropInRegistrationHostTemplateId,
        hostEmailPersonalisation).recover {
        case e => log.error(s"Error sending drop-in registration email to host $email", e)
      }

    }
  }

}
