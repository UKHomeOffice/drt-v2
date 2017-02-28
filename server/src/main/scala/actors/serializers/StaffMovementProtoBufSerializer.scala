package actors.serializers

import java.util.UUID

import actors.StaffMovements
import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.StaffMovementMessages.StaffMovementsMessage
import spatutorial.shared.{MilliDate, StaffMovement}

class StaffMovementProtoBufSerializer extends SerializerWithStringManifest{
  override def identifier: Int = 1

  override def manifest(o: AnyRef): String = o.getClass.getName

  final val StaffMovementsManifest = classOf[StaffMovementsMessage].getName

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case sm: StaffMovementsMessage => sm.toByteArray
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case StaffMovementsManifest =>
        val smm = StaffMovementsMessage.parseFrom(bytes)

        StaffMovements(smm.staffMovements.map(sm => StaffMovement(
          sm.terminalName.getOrElse(""),
          sm.reason.getOrElse(""),
          MilliDate(sm.time.getOrElse(0)),
          sm.delta.getOrElse(0),
          UUID.fromString(sm.uUID.getOrElse("")),
          sm.queueName
        )).toList)
    }
  }
}
