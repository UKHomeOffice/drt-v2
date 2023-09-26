package controllers.application

import controllers.Application
import drt.shared.{DropIn, DropInRegistration}
import play.api.mvc.{Action, AnyContent}
import slickdb.DropInRow
import uk.gov.homeoffice.drt.auth.Roles.BorderForceStaff
import upickle.default.write

import scala.concurrent.Future
import scala.util.Try

trait WithDropIns {

  self: Application =>

  lazy val dropInRegistrationTemplateId = config.get[String]("notifications.dropIn-registration-templateId")

  lazy val dropInRegistrationHostTemplateId = config.get[String]("notifications.dropIn-registration-host-templateId")

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
          log.info(s"Received drop-ins booking data")
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

  def sendDropInRegistrationEmails(email: String, dropIns: Seq[DropInRow]) = {

    dropIns.map { dropIn =>
      val personalisation = emailNotification
        .dropInRegistrationConfirmation(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), email, dropIn)
      val hostEmailPersonalisation = emailNotification
        .dropInRegistrationHost(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), dropInHostEmail, email, dropIn)

      emailNotification.sendRequest(govNotifyReference,
        email,
        dropInRegistrationTemplateId,
        personalisation).recover {
        case e => log.error(s"Error sending drop-in registration email to user $email", e)
      }

      emailNotification.sendRequest(govNotifyReference,
        dropInHostEmail,
        dropInRegistrationHostTemplateId,
        hostEmailPersonalisation).recover {
        case e => log.error(s"Error sending drop-in registration email to host $email", e)
      }

    }
  }

}
