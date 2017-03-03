package actors.serializers

import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.FlightsMessage.FlightsMessage
import server.protobuf.messages.StaffMovementMessages.StaffMovementsMessage

class FlightsProtoBufSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 9002

  override def manifest(targetObject: AnyRef): String = {
    targetObject.getClass.getName
  }

  final val manifestName = classOf[FlightsMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case sms: FlightsMessage => sms.toByteArray
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case `manifestName` => FlightsMessage.parseFrom(bytes)
    }
  }
}
