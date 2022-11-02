package email

import drt.shared.{NegativeFeedback, PositiveFeedback}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.service.notify.NotificationClient

import java.util
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.Try


class GovNotifyEmail(apiKey: String) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  val client = new NotificationClient(apiKey)

  def positivePersonalisationData(feedbackData: PositiveFeedback): util.Map[String, String] = {
    Map(
      "url" -> feedbackData.url,
      "email" -> feedbackData.email,
      "portCode" -> feedbackData.portCode,
    ).asJava
  }

  def negativePersonalisationData(feedbackData: NegativeFeedback): util.Map[String, String] = {
    Map(
      "portCode" -> feedbackData.portCode,
      "email" -> feedbackData.email,
      "url" -> feedbackData.url,
      "whatUserWasDoing" -> feedbackData.whatUserWasDoing,
      "whatWentWrong" -> feedbackData.whatWentWrong,
      "whatToImprove" -> feedbackData.whatToImprove,
    ).asJava
  }

  def sendRequest(reference: String, emailAddress: String, templateId: String, personalisation: util.Map[String, String]): Try[Any] = {
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
