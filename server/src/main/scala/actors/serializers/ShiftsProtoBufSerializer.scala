package actors.serializers

import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.ShiftMessage.ShiftsMessage

class ShiftsProtoBufSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 9003

  override def manifest(targetObject: AnyRef): String = {
    targetObject.getClass.getName
  }

  final val ShiftsManifest = classOf[ShiftsMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case sm: ShiftsMessage => sm.toByteArray
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case ShiftsManifest => ShiftsMessage.parseFrom(bytes)
    }
  }
}
