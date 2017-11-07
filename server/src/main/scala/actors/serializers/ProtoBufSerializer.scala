package actors.serializers

import akka.serialization.SerializerWithStringManifest
import org.slf4j.LoggerFactory
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchStateSnapshotMessage}
import server.protobuf.messages.FixedPointMessage.FixedPointsStateSnapshotMessage
import server.protobuf.messages.FlightsMessage.{FlightStateSnapshotMessage, FlightsDiffMessage}
import server.protobuf.messages.ShiftMessage.ShiftStateSnapshotMessage
import server.protobuf.messages.StaffMovementMessages.StaffMovementsStateSnapshotMessage
import server.protobuf.messages.VoyageManifest.{VoyageManifestLatestFileNameMessage, VoyageManifestMessage, VoyageManifestStateSnapshotMessage, VoyageManifestsMessage}
import services.SDate

class ProtoBufSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 9001

  override def manifest(targetObject: AnyRef): String = targetObject.getClass.getName

  final val CrunchDiff: String = classOf[CrunchDiffMessage].getName
  final val FlightsDiff: String = classOf[FlightsDiffMessage].getName
  final val CrunchStateSnapshot: String = classOf[CrunchStateSnapshotMessage].getName
  final val ShiftStateSnapshot: String = classOf[ShiftStateSnapshotMessage].getName
  final val FixedPointsStateSnapshot: String = classOf[FixedPointsStateSnapshotMessage].getName
  final val StaffMovementsStateSnapshot: String = classOf[StaffMovementsStateSnapshotMessage].getName
  final val FlightStateSnapshot: String = classOf[FlightStateSnapshotMessage].getName
  final val VoyageManifestStateSnapshot: String = classOf[VoyageManifestStateSnapshotMessage].getName
  final val VoyageManifestLatestFileName: String = classOf[VoyageManifestLatestFileNameMessage].getName
  final val VoyageManifests: String = classOf[VoyageManifestsMessage].getName
  final val VoyageManifest: String = classOf[VoyageManifestMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case m: CrunchDiffMessage => m.toByteArray
      case m: FlightsDiffMessage => m.toByteArray
      case m: CrunchStateSnapshotMessage => m.toByteArray
      case m: ShiftStateSnapshotMessage => m.toByteArray
      case m: FixedPointsStateSnapshotMessage => m.toByteArray
      case m: StaffMovementsStateSnapshotMessage => m.toByteArray
      case m: FlightStateSnapshotMessage => m.toByteArray
      case m: VoyageManifestStateSnapshotMessage => m.toByteArray
      case m: VoyageManifestLatestFileNameMessage => m.toByteArray
      case m: VoyageManifestsMessage => m.toByteArray
      case m: VoyageManifestMessage => m.toByteArray
    }
  }

  val log = LoggerFactory.getLogger(getClass)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case CrunchDiff =>
        val m = CrunchDiffMessage.parseFrom(bytes)
        log.info(s"CrunchDiffMessage: ${bytes.length} bytes, ${m.staffMinutesToUpdate.length} staff, ${m.flightsToUpdate.length} flight, ${m.crunchMinutesToUpdate.length} crunch, ${SDate(m.createdAt.getOrElse(0L)).toLocalDateTimeString()}")
        m
      case FlightsDiff => FlightsDiffMessage.parseFrom(bytes)
      case CrunchStateSnapshot => CrunchStateSnapshotMessage.parseFrom(bytes)
      case ShiftStateSnapshot => ShiftStateSnapshotMessage.parseFrom(bytes)
      case FixedPointsStateSnapshot => FixedPointsStateSnapshotMessage.parseFrom(bytes)
      case StaffMovementsStateSnapshot => StaffMovementsStateSnapshotMessage.parseFrom(bytes)
      case FlightStateSnapshot => FlightStateSnapshotMessage.parseFrom(bytes)
      case VoyageManifestStateSnapshot => VoyageManifestStateSnapshotMessage.parseFrom(bytes)
      case VoyageManifestLatestFileName => VoyageManifestLatestFileNameMessage.parseFrom(bytes)
      case VoyageManifests =>
        log.info(s"VoyageManifestsMessage: ${bytes.length} bytes")
        VoyageManifestsMessage.parseFrom(bytes)
      case VoyageManifest => VoyageManifestMessage.parseFrom(bytes)
    }
  }
}
