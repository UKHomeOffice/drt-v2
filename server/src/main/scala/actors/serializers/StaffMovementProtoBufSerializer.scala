package actors.serializers

import java.util.UUID

import actors.StaffMovements
import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.StaffMovementMessages.{StaffMovementMessage, StaffMovementsMessage}
import spatutorial.shared.{MilliDate, StaffMovement}

class StaffMovementProtoBufSerializer extends SerializerWithStringManifest {

  println("We are making the serializer")

  override def identifier: Int = 1

  override def manifest(o: AnyRef): String = o.getClass.getName

  final val StaffMovementsManifest = classOf[StaffMovementsMessage].getName

  override def toBinary(o: AnyRef): Array[Byte] = {
    println("We are inside toBinary")
    o match {
      case sms: StaffMovements =>
        println(s"We are making binary $sms")
        StaffMovementsMessage(sms.staffMovements.map(sm =>
          StaffMovementMessage(
            Some(sm.terminalName),
            Some(sm.reason),
            Some(sm.time.millisSinceEpoch),
            Some(sm.delta),
            Some(sm.uUID.toString),
            sm.queue
          )
        )).toByteArray
      case error =>
        println(s"(serializing) We failed to match against $error")
        throw new Exception("oh dear")
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    println("We are inside fromBinary")

    manifest match {
      case StaffMovementsManifest =>

        val smm = StaffMovementsMessage.parseFrom(bytes)
        println(s"We are deserializing binary $smm")

        StaffMovements(smm.staffMovements.map(sm => StaffMovement(
          sm.terminalName.getOrElse(""),
          sm.reason.getOrElse(""),
          MilliDate(sm.time.getOrElse(0)),
          sm.delta.getOrElse(0),
          UUID.fromString(sm.uUID.getOrElse("")),
          sm.queueName
        )).toList)
      case error =>
        println(s"(deserializing) We failed to match against $error for $bytes")
        throw new Exception("oh dear")
    }
  }
}
