package controllers.application

import controllers.Application
import drt.shared.Seminar
import play.api.mvc.{Action, AnyContent}
import slickdb.SeminarRow
import uk.gov.homeoffice.drt.auth.Roles.BorderForceStaff
import upickle.default.write

import scala.concurrent.Future
import scala.util.Try

trait withSeminars {

  self: Application =>

  lazy val seminarRegistrationTemplateId = config.get[String]("notifications.seminar-registration-templateId")

  lazy val seminarRegistrationHostTemplateId = config.get[String]("notifications.seminar-registration-host-templateId")

  lazy val seminarHostEmail = config.get[String]("notifications.seminar-host-email")

  import drt.shared.Seminar._
  def seminars(): Action[AnyContent] = Action.async { _ =>
    val seminarsJson: Future[Seq[Seminar]] = ctrl.seminarService.getFuturePublishedSeminars()
    seminarsJson.map(seminars => Ok(write(seminars)))
  }

  def registerSeminars: Action[AnyContent] = authByRole(BorderForceStaff) {
    Action { implicit request =>
      import spray.json.DefaultJsonProtocol._
      import spray.json._
      val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
      request.body.asText match {
        case Some(content) =>
          log.info(s"Received seminars booking data")
          Try(content.parseJson.convertTo[String])
            .map { id =>
              ctrl.seminarRegistrationService.registerSeminars(userEmail, id).map { _ =>
                ctrl.seminarService.getSeminars(Seq(id)).map(sendSeminarRegistrationEmail(userEmail, _))
              }.recover {
                case e => log.warning(s"Error while db insert for seminar registration", e)
                  BadRequest(s"Failed to register seminars for user $userEmail")
              }
              Ok("Successfully registered seminars")
            }.recover {
            case e => log.warning(s"Error while seminar registration", e)
              BadRequest(s"Failed to register seminars for user $userEmail")
          }.getOrElse(BadRequest("Failed to parse json"))
        case None => BadRequest("No content")
      }
    }
  }

  def sendSeminarRegistrationEmail(email: String, seminars: Seq[SeminarRow]) = {

    seminars.map { seminar =>
      val personalisation = emailNotification
        .seminarRegistrationConfirmation(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), email, seminar)
      val hostEmailPersonalisation = emailNotification
        .seminarRegistrationHost(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), seminarHostEmail, email, seminar)

      emailNotification.sendRequest(govNotifyReference,
        email,
        seminarRegistrationTemplateId,
        personalisation).recover {
        case e => log.error(s"Error sending seminar registration email to user $email", e)
      }

      emailNotification.sendRequest(govNotifyReference,
        seminarHostEmail,
        seminarRegistrationHostTemplateId,
        hostEmailPersonalisation).recover {
        case e => log.error(s"Error sending seminar registration email to host $email", e)
      }

    }
  }

}
