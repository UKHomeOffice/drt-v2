package email

import drt.shared.NegativeFeedback
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.service.notify.NotificationClient

import java.util
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.Try


class GovNotifyEmail(apiKey: String) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  val client = new NotificationClient(apiKey)

  def positivePersonalisationData(url:String): util.Map[String, String] = {
    Map(
      "url" -> url
    ).asJava
  }

  def negativePersonalisationData(feedbackData: NegativeFeedback): util.Map[String, String] = {
    val contactMe = s"Contact email: ${feedbackData.feedbackUserEmail}."

    Map(
    "url" -> feedbackData.url,
    "feedbackUserEmail" -> feedbackData.feedbackUserEmail,
    "whatUserDoing" -> feedbackData.whatUserDoing,
    "whatWentWrong" -> feedbackData.whatWentWrong,
    "whatToImprove" -> feedbackData.whatToImprove,
    "contactMe" -> contactMe
    ).asJava
  }

  def sendRequest(reference:String,emailAddress: String, templateId: String, personalisation: util.Map[String, String]): Try[Any] = {
    Try(
      client.sendEmail(templateId,
        emailAddress,
        personalisation,
        reference)
    ).recover {
      case e => log.error(s"Unable to sendEmail", e)
    }
  }
}
