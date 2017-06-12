package actors.serializers

import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.FlightsMessage.FlightStateSnapshotMessage

class FlightStateSnapshotProtoBufSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 9004

  override def manifest(targetObject: AnyRef): String = {
    targetObject.getClass.getName
  }

  final val manifestName = classOf[FlightStateSnapshotMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case sms: FlightStateSnapshotMessage => sms.toByteArray
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case `manifestName` => FlightStateSnapshotMessage.parseFrom(bytes)
    }
  }
}
