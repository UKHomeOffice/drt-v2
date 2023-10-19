package email

import com.google.inject.Inject
import drt.shared.{NegativeFeedback, PositiveFeedback}
import org.slf4j.{Logger, LoggerFactory}
import slickdb.DropInRow
import uk.gov.service.notify.NotificationClient

import java.util
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.Try


class GovNotifyEmail @Inject()(apiKey: String) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  val client = new NotificationClient(apiKey)

  def getFirstName(email: String): String = {
    Try(email.split("\\.").head.toLowerCase.capitalize).getOrElse(email)
  }

  def dropInRegistrationHost(teamEmail: String, hostEmail: String, registeredUserEmail: String, dropIn: DropInRow) = {
    Map(
      "teamEmail" -> teamEmail,
      "registeredUserEmail" -> registeredUserEmail,
      "hostUsername" -> getFirstName(hostEmail),
      "title" -> dropIn.title,
      "dropInDate" -> dropIn.getDate,
      "startTime" -> dropIn.getStartTime,
      "endTime" -> dropIn.getEndTime,
      "meetingLink" -> dropIn.meetingLink.getOrElse(""),
    ).asJava
  }

  def dropInRegistrationConfirmation(teamEmail: String, email: String, dropIn: DropInRow): util.Map[String, String] = {
    Map(
      "teamEmail" -> teamEmail,
      "requesterUsername" -> getFirstName(email),
      "title" -> dropIn.title,
      "dropInDate" -> dropIn.getDate,
      "startTime" -> dropIn.getStartTime,
      "endTime" -> dropIn.getEndTime,
      "meetingLink" -> dropIn.meetingLink.getOrElse(""),
    ).asJava
  }

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
