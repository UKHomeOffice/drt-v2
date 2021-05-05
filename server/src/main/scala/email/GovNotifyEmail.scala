package email


import controllers.application.FeedbackData
import org.slf4j.{Logger, LoggerFactory}

import java.util
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.Try
import uk.gov.service.notify.{NotificationClient, SendEmailResponse}


class GovNotifyEmail(apiKey: String) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  val client = new NotificationClient(apiKey)

  def positivePersonalisationData(feedbackUserEmail: String): util.Map[String, String] = {
    Map(
      "feedbackUserEmail" -> feedbackUserEmail
    ).asJava
  }

  def negativePersonalisationData(feedbackData: FeedbackData): util.Map[String, String] = {
    val contactMe = if (feedbackData.contactMe) "User is happy to be contacted" else "User is not happy to be contacted"
    Map(
      "feedbackUserEmail" -> feedbackData.feedbackUserEmail,
      "whatUserDoing" -> feedbackData.whatToImprove,
      "whatWentWrong" -> feedbackData.whatWentWrong,
      "whatToImprove" -> feedbackData.whatUserDoing,
      "contactMe" -> contactMe
    ).asJava
  }

  def sendRequest(emailAddress: String, templateId: String, personalisation: util.Map[String, String]) = {
    Try(
      client.sendEmail(templateId,
        emailAddress,
        personalisation,
        "DRT-test")
    ).recover {
      case e => log.error(s"Unable to sendEmail", e)
    }
  }
}
