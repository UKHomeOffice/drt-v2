package actors.serializers

import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.StaffMovementMessages.StaffMovementsMessage

class StaffMovementProtoBufSerializer extends SerializerWithStringManifest {

  println("We are making the serializer")

  override def identifier: Int = 9001

  override def manifest(o: AnyRef): String = {
    println(s"The manifest is called ${o.getClass.getName}")
    o.getClass.getName
  }

  final val StaffMovementsManifest = classOf[StaffMovementsMessage].getName

  override def toBinary(o: AnyRef): Array[Byte] = {
    println("We are inside toBinary")
    o match {
      case sms: StaffMovementsMessage => sms.toByteArray
      case error =>
        println(s"(serializing) We failed to match against $error")
        throw new Exception("oh dear")
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    println("We are inside fromBinary")

    manifest match {
      case StaffMovementsManifest => StaffMovementsMessage.parseFrom(bytes)
      case error =>
        println(s"(deserializing) We failed to match against $error for $bytes")
        throw new Exception("oh dear")
    }
  }
}
