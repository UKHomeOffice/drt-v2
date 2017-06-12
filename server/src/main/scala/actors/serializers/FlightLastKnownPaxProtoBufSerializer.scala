package actors.serializers

import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.FlightsMessage.FlightLastKnownPaxMessage

class FlightLastKnownPaxProtoBufSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 9005

  override def manifest(targetObject: AnyRef): String = {
    targetObject.getClass.getName
  }

  final val manifestName = classOf[FlightLastKnownPaxMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case sms: FlightLastKnownPaxMessage => sms.toByteArray
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case `manifestName` => FlightLastKnownPaxMessage.parseFrom(bytes)
    }
  }
}
