package actors.serializers

import akka.serialization.SerializerWithStringManifest
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchStateSnapshotMessage}

class ProtoBufSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 9001

  override def manifest(targetObject: AnyRef): String = targetObject.getClass.getName

  final val CrunchDiff = classOf[CrunchDiffMessage].getName
  final val CrunchStateSnapshot = classOf[CrunchStateSnapshotMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case m: CrunchDiffMessage => m.toByteArray
      case m: CrunchStateSnapshotMessage => m.toByteArray
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case CrunchDiff => CrunchDiffMessage.parseFrom(bytes)
      case CrunchStateSnapshot => CrunchStateSnapshotMessage.parseFrom(bytes)
    }
  }
}
