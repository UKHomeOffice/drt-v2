package controllers.application

import controllers.Application
import drt.shared.{NegativeFeedback, PositiveFeedback}
import email.GovNotifyEmail
import play.api.mvc.{Action, AnyContent}
import slickdb.SeminarRow
import upickle.default.read

trait WithEmailNotification {
  self: Application =>

  val emailNotification = new GovNotifyEmail(govNotifyApiKey)

  def sendSeminarRegistrationEmail(email: String, seminars: Seq[SeminarRow]) = {
    val protocol = if (isSecure) "https://" else "http://"
    val portOrLocal = if (isSecure) airportConfig.portCode.toString.toLowerCase + "." + baseDomain else baseDomain + ":9000"

    seminars.map { seminar =>
      val icsFileLink = s"$protocol$portOrLocal/seminar/calendarInvite/${seminar.id.getOrElse("")}"
      val personalisation = emailNotification
        .seminarRegistrationConfirmation(icsFileLink, contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), email, seminar)
      val hostEmailPersonalisation = emailNotification
        .seminarRegistrationHost(icsFileLink, contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), seminarHostEmail, email, seminar)

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

  def feedBack(feedback: String): Action[AnyContent] = {
    Action { request =>
      feedback match {
        case "positive" =>
          request.body.asText match {
            case Some(json) =>
              val personalisation = emailNotification.positivePersonalisationData(read(json)(PositiveFeedback.rw))
              emailNotification.sendRequest(govNotifyReference,
                contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"),
                positiveFeedbackTemplateId,
                personalisation)
              Accepted
            case None =>
              BadRequest
          }
        case "negative" => request.body.asText match {
          case Some(json) =>
            val personalisation = emailNotification.negativePersonalisationData(read(json)(NegativeFeedback.rw))
            emailNotification.sendRequest(govNotifyReference,
              contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"),
              negativeFeedbackTemplateId, personalisation)
            Accepted
          case None =>
            BadRequest
        }
      }
    }
  }
}
