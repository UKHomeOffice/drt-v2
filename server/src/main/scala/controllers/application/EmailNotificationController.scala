package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import drt.shared.{NegativeFeedback, PositiveFeedback}
import email.GovNotifyEmail
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import upickle.default.read

class EmailNotificationController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface, govNotifyEmail: GovNotifyEmail) extends AuthController(cc, ctrl) {

  lazy val contactEmail: Option[String] = config.getOptional[String]("contact-email")

  lazy val negativeFeedbackTemplateId = config.get[String]("notifications.negative-feedback-templateId")

  lazy val positiveFeedbackTemplateId = config.get[String]("notifications.positive-feedback-templateId")

  lazy val govNotifyReference = config.get[String]("notifications.reference")

  def feedBack(feedback: String): Action[AnyContent] = {
    Action { request =>
      feedback match {
        case "positive" =>
          request.body.asText match {
            case Some(json) =>
              val personalisation = govNotifyEmail.positivePersonalisationData(read(json)(PositiveFeedback.rw))
              govNotifyEmail.sendRequest(govNotifyReference,
                contactEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk"),
                positiveFeedbackTemplateId,
                personalisation)
              Accepted
            case None =>
              BadRequest
          }
        case "negative" => request.body.asText match {
          case Some(json) =>
            val personalisation = govNotifyEmail.negativePersonalisationData(read(json)(NegativeFeedback.rw))
            govNotifyEmail.sendRequest(govNotifyReference,
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
