package actors.serializers

import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.StaffMovementMessages.StaffMovementsMessage

class StaffMovementProtoBufSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 9001

  override def manifest(targetObject: AnyRef): String = {
    targetObject.getClass.getName
  }

  final val StaffMovementsManifest = classOf[StaffMovementsMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case sms: StaffMovementsMessage => sms.toByteArray
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case StaffMovementsManifest => StaffMovementsMessage.parseFrom(bytes)
    }
  }
}
