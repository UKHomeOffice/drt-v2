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
    seminars.map { seminar =>
      val personalisation = emailNotification
        .seminarRegistrationConfirmation(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), email, seminar)
      val hostEmailPersonalisation = emailNotification
        .seminarRegistrationHost(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), seminarHostEmail, email, seminar)

      emailNotification.sendRequest(govNotifyReference,
        email,
        seminarRegistrationTemplateId,
        personalisation)

      emailNotification.sendRequest(govNotifyReference,
        seminarHostEmail,
        seminarRegistrationHostTemplateId,
        hostEmailPersonalisation)

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
