package actors.serializers

import drt.shared.Alert
import uk.gov.homeoffice.drt.protobuf.messages.Alert.{Alert => ProtobufAlert}

object AlertMessageConversion {

  def alertToMessage(alert: Alert): ProtobufAlert = ProtobufAlert(
    title = Option(alert.title),
    message = Option(alert.message),
    alertClass = Option(alert.alertClass),
    expires = Option(alert.expires),
    createdAt = Option(alert.createdAt)
  )

  def alertFromMessage(alertMessage: ProtobufAlert): Option[Alert] = for {
    title <- alertMessage.title
    message <- alertMessage.message
    alertClass <- alertMessage.alertClass
    expires <- alertMessage.expires
    createdAt <- alertMessage.createdAt
  } yield Alert(title, message, alertClass, expires, createdAt)

}
