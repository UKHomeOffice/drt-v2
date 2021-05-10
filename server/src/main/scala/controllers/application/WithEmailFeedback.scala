package controllers.application

import controllers.Application
import email.GovNotifyEmail
import play.api.mvc.{Action, AnyContent}
import upickle.default.{macroRW, read, ReadWriter => RW}

case class FeedbackData(feedbackUserEmail: String,
                        whatUserDoing: String,
                        whatWentWrong: String,
                        whatToImprove: String,
                        contactMe: Boolean,
                        url :String)

trait WithEmailFeedback {
  self: Application =>
  implicit val rw: RW[FeedbackData] = macroRW

  val emailNotification = new GovNotifyEmail(govNotifyApiKey)

  def feedBack(feedback: String): Action[AnyContent] = {
    Action { request =>
      feedback match {
        case "positive" =>
          request.body.asText match {
            case Some(json) =>
              val personalisation = emailNotification.positivePersonalisationData(read(json).url)
              emailNotification.sendRequest(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), negativeFeedbackTemplateId, personalisation)
              Accepted
            case None =>
              BadRequest
          }
        case "negative" => request.body.asText match {
          case Some(json) =>
            val personalisation = emailNotification.negativePersonalisationData(read(json))
            emailNotification.sendRequest(contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"), positiveFeedbackTemplateId, personalisation)
            Accepted
          case None =>
            BadRequest
        }
      }

    }
  }
}
