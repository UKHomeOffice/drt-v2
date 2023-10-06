package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import drt.shared.{NegativeFeedback, PositiveFeedback}
import email.GovNotifyEmail
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import upickle.default.read

class EmailNotificationController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  lazy val contactEmail: Option[String] = config.getOptional[String]("contact-email")

  lazy val govNotifyApiKey = config.get[String]("notifications.gov-notify-api-key")

  lazy val negativeFeedbackTemplateId = config.get[String]("notifications.negative-feedback-templateId")

  lazy val positiveFeedbackTemplateId = config.get[String]("notifications.positive-feedback-templateId")

  lazy val govNotifyReference = config.get[String]("notifications.reference")


  val emailNotification: GovNotifyEmail = new GovNotifyEmail(govNotifyApiKey)

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
