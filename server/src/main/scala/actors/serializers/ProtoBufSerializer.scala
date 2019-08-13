package actors.serializers

import akka.serialization.SerializerWithStringManifest
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.Alert.{Alert, AlertSnapshotMessage}
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchStateSnapshotMessage}
import server.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FeedStatusesMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import server.protobuf.messages.RegisteredArrivalMessage.{RegisteredArrivalMessage, RegisteredArrivalsMessage}
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import server.protobuf.messages.VoyageManifest.{VoyageManifestLatestFileNameMessage, VoyageManifestMessage, VoyageManifestStateSnapshotMessage, VoyageManifestsMessage}

class ProtoBufSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 9001

  override def manifest(targetObject: AnyRef): String = targetObject.getClass.getName

  final val CrunchDiff: String                    = classOf[CrunchDiffMessage].getName
  final val CrunchStateSnapshot: String           = classOf[CrunchStateSnapshotMessage].getName
  final val Shifts: String                        = classOf[ShiftsMessage].getName
  final val ShiftStateSnapshot: String            = classOf[ShiftStateSnapshotMessage].getName
  final val Shift: String                         = classOf[ShiftMessage].getName
  final val FixedPoints: String                   = classOf[FixedPointsMessage].getName
  final val FixedPointsStateSnapshot: String      = classOf[FixedPointsStateSnapshotMessage].getName
  final val FixedPoint: String                    = classOf[FixedPointMessage].getName
  final val StaffMovements: String                = classOf[StaffMovementsMessage].getName
  final val StaffMovementsStateSnapshot: String   = classOf[StaffMovementsStateSnapshotMessage].getName
  final val StaffMovement: String                 = classOf[StaffMovementMessage].getName
  final val RemoveStaffMovement: String           = classOf[RemoveStaffMovementMessage].getName
  final val FlightsDiff: String                   = classOf[FlightsDiffMessage].getName
  final val FlightStateSnapshot: String           = classOf[FlightStateSnapshotMessage].getName
  final val FeedStatus: String                    = classOf[FeedStatusMessage].getName
  final val FeedStatuses: String                  = classOf[FeedStatusesMessage].getName
  final val VoyageManifestStateSnapshot: String   = classOf[VoyageManifestStateSnapshotMessage].getName
  final val VoyageManifestLatestFileName: String  = classOf[VoyageManifestLatestFileNameMessage].getName
  final val VoyageManifests: String               = classOf[VoyageManifestsMessage].getName
  final val VoyageManifest: String                = classOf[VoyageManifestMessage].getName
  final val Alerts: String                        = classOf[Alert].getName
  final val AlertSnapshot: String                 = classOf[AlertSnapshotMessage].getName
  final val RegisteredArrivals: String            = classOf[RegisteredArrivalsMessage].getName
  final val RegisteredArrival: String             = classOf[RegisteredArrivalMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = {
    objectToSerialize match {
      case m: CrunchDiffMessage => m.toByteArray
      case m: CrunchStateSnapshotMessage => m.toByteArray
      case m: ShiftsMessage => m.toByteArray
      case m: ShiftStateSnapshotMessage => m.toByteArray
      case m: ShiftMessage => m.toByteArray
      case m: FixedPointsMessage => m.toByteArray
      case m: FixedPointsStateSnapshotMessage => m.toByteArray
      case m: FixedPointMessage => m.toByteArray
      case m: StaffMovementsMessage => m.toByteArray
      case m: StaffMovementsStateSnapshotMessage => m.toByteArray
      case m: StaffMovementMessage => m.toByteArray
      case m: RemoveStaffMovementMessage => m.toByteArray
      case m: FlightsDiffMessage => m.toByteArray
      case m: FlightStateSnapshotMessage => m.toByteArray
      case m: FeedStatusMessage => m.toByteArray
      case m: FeedStatusesMessage => m.toByteArray
      case m: VoyageManifestStateSnapshotMessage => m.toByteArray
      case m: VoyageManifestLatestFileNameMessage => m.toByteArray
      case m: VoyageManifestsMessage => m.toByteArray
      case m: VoyageManifestMessage => m.toByteArray
      case m: Alert => m.toByteArray
      case m: AlertSnapshotMessage => m.toByteArray
      case m: RegisteredArrivalMessage => m.toByteArray
      case m: RegisteredArrivalsMessage => m.toByteArray
    }
  }

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case CrunchDiff                     => CrunchDiffMessage.parseFrom(bytes)
      case CrunchStateSnapshot            => CrunchStateSnapshotMessage.parseFrom(bytes)
      case Shifts                         => ShiftsMessage.parseFrom(bytes)
      case ShiftStateSnapshot             => ShiftStateSnapshotMessage.parseFrom(bytes)
      case Shift                          => ShiftMessage.parseFrom(bytes)
      case FixedPoints                    => FixedPointsMessage.parseFrom(bytes)
      case FixedPointsStateSnapshot       => FixedPointsStateSnapshotMessage.parseFrom(bytes)
      case FixedPoint                     => FixedPointMessage.parseFrom(bytes)
      case StaffMovements                 => StaffMovementsMessage.parseFrom(bytes)
      case StaffMovementsStateSnapshot    => StaffMovementsStateSnapshotMessage.parseFrom(bytes)
      case StaffMovement                  => StaffMovementMessage.parseFrom(bytes)
      case RemoveStaffMovement            => RemoveStaffMovementMessage.parseFrom(bytes)
      case FlightsDiff                    => FlightsDiffMessage.parseFrom(bytes)
      case FlightStateSnapshot            => FlightStateSnapshotMessage.parseFrom(bytes)
      case FeedStatus                     => FeedStatusMessage.parseFrom(bytes)
      case FeedStatuses                   => FeedStatusesMessage.parseFrom(bytes)
      case VoyageManifestStateSnapshot    => VoyageManifestStateSnapshotMessage.parseFrom(bytes)
      case VoyageManifestLatestFileName   => VoyageManifestLatestFileNameMessage.parseFrom(bytes)
      case VoyageManifests                => VoyageManifestsMessage.parseFrom(bytes)
      case VoyageManifest                 => VoyageManifestMessage.parseFrom(bytes)
      case AlertSnapshot                  => AlertSnapshotMessage.parseFrom(bytes)
      case Alerts                         => Alert.parseFrom(bytes)
      case RegisteredArrival              => RegisteredArrivalMessage.parseFrom(bytes)
      case RegisteredArrivals             => RegisteredArrivalsMessage.parseFrom(bytes)
    }
  }
}
