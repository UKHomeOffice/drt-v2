package actors.serializers

import akka.serialization.SerializerWithStringManifest
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.Alert.{Alert, AlertSnapshotMessage}
import server.protobuf.messages.CrunchState.{CrunchRequestMessage, _}
import server.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import server.protobuf.messages.FlightsMessage._
import server.protobuf.messages.FlightsSummary.FlightsSummaryMessage
import server.protobuf.messages.PaxMessage.{OriginTerminalPaxCountsMessage, OriginTerminalPaxCountsMessages, PaxCountMessage}
import server.protobuf.messages.RedListUpdates.{AdditionMessage, RedListUpdateMessage, RedListUpdatesMessage, RemovalMessage, RemoveUpdateMessage, SetRedListUpdateMessage}
import server.protobuf.messages.RegisteredArrivalMessage.{RegisteredArrivalMessage, RegisteredArrivalsMessage}
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import server.protobuf.messages.TerminalQueuesSummary.TerminalQueuesSummaryMessage
import server.protobuf.messages.VoyageManifest.{VoyageManifestLatestFileNameMessage, VoyageManifestMessage, VoyageManifestStateSnapshotMessage, VoyageManifestsMessage}

class ProtoBufSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 9001

  override def manifest(targetObject: AnyRef): String = targetObject.getClass.getName

  final val CrunchDiff: String = classOf[CrunchDiffMessage].getName
  final val CrunchStateSnapshot: String = classOf[CrunchStateSnapshotMessage].getName
  final val CrunchMinutes: String = classOf[CrunchMinutesMessage].getName
  final val FlightsWithSplits: String = classOf[FlightsWithSplitsMessage].getName
  final val FlightsWithSplitsDiff: String = classOf[FlightsWithSplitsDiffMessage].getName
  final val Shifts: String = classOf[ShiftsMessage].getName
  final val ShiftStateSnapshot: String = classOf[ShiftStateSnapshotMessage].getName
  final val Shift: String = classOf[ShiftMessage].getName
  final val FixedPoints: String = classOf[FixedPointsMessage].getName
  final val FixedPointsStateSnapshot: String = classOf[FixedPointsStateSnapshotMessage].getName
  final val FixedPoint: String = classOf[FixedPointMessage].getName
  final val StaffMovements: String = classOf[StaffMovementsMessage].getName
  final val StaffMovementsStateSnapshot: String = classOf[StaffMovementsStateSnapshotMessage].getName
  final val StaffMovement: String = classOf[StaffMovementMessage].getName
  final val RemoveStaffMovement: String = classOf[RemoveStaffMovementMessage].getName
  final val FlightsDiff: String = classOf[FlightsDiffMessage].getName
  final val FlightStateSnapshot: String = classOf[FlightStateSnapshotMessage].getName
  final val Flight: String = classOf[FlightMessage].getName
  final val FeedStatus: String = classOf[FeedStatusMessage].getName
  final val FeedStatuses: String = classOf[FeedStatusesMessage].getName
  final val UniqueArrival: String = classOf[UniqueArrivalMessage].getName
  final val VoyageManifestStateSnapshot: String = classOf[VoyageManifestStateSnapshotMessage].getName
  final val VoyageManifestLatestFileName: String = classOf[VoyageManifestLatestFileNameMessage].getName
  final val VoyageManifests: String = classOf[VoyageManifestsMessage].getName
  final val VoyageManifest: String = classOf[VoyageManifestMessage].getName
  final val Alerts: String = classOf[Alert].getName
  final val AlertSnapshot: String = classOf[AlertSnapshotMessage].getName
  final val RegisteredArrival: String = classOf[RegisteredArrivalMessage].getName
  final val RegisteredArrivals: String = classOf[RegisteredArrivalsMessage].getName
  final val TerminalQueuesSummary: String = classOf[TerminalQueuesSummaryMessage].getName
  final val FlightsSummary: String = classOf[FlightsSummaryMessage].getName
  final val StaffMinutes: String = classOf[StaffMinutesMessage].getName
  final val PaxCount: String = classOf[PaxCountMessage].getName
  final val OriginTerminalPaxCounts: String = classOf[OriginTerminalPaxCountsMessage].getName
  final val OriginTerminalPaxCountsMgs: String = classOf[OriginTerminalPaxCountsMessages].getName
  final val Days: String = classOf[DaysMessage].getName
  final val RemoveDay: String = classOf[RemoveDayMessage].getName
  final val CrunchRequest: String = classOf[CrunchRequestMessage].getName
  final val CrunchRequests: String = classOf[CrunchRequestsMessage].getName
  final val RemoveCrunchRequest: String = classOf[RemoveCrunchRequestMessage].getName
  final val SetRedListUpdate: String = classOf[SetRedListUpdateMessage].getName
  final val RedListUpdates: String = classOf[RedListUpdatesMessage].getName
  final val RedListUpdate: String = classOf[RedListUpdateMessage].getName
  final val Addition: String = classOf[AdditionMessage].getName
  final val Removal: String = classOf[RemovalMessage].getName
  final val RemoveUpdate: String = classOf[RemoveUpdateMessage].getName

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = objectToSerialize match {
    case m: GeneratedMessage => m.toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case CrunchDiff => CrunchDiffMessage.parseFrom(bytes)
      case CrunchStateSnapshot => CrunchStateSnapshotMessage.parseFrom(bytes)
      case Shifts => ShiftsMessage.parseFrom(bytes)
      case ShiftStateSnapshot => ShiftStateSnapshotMessage.parseFrom(bytes)
      case Shift => ShiftMessage.parseFrom(bytes)
      case FixedPoints => FixedPointsMessage.parseFrom(bytes)
      case FixedPointsStateSnapshot => FixedPointsStateSnapshotMessage.parseFrom(bytes)
      case FixedPoint => FixedPointMessage.parseFrom(bytes)
      case StaffMovements => StaffMovementsMessage.parseFrom(bytes)
      case StaffMovementsStateSnapshot => StaffMovementsStateSnapshotMessage.parseFrom(bytes)
      case StaffMovement => StaffMovementMessage.parseFrom(bytes)
      case RemoveStaffMovement => RemoveStaffMovementMessage.parseFrom(bytes)
      case FlightsDiff => FlightsDiffMessage.parseFrom(bytes)
      case FlightStateSnapshot => FlightStateSnapshotMessage.parseFrom(bytes)
      case Flight => FlightMessage.parseFrom(bytes)
      case UniqueArrival => UniqueArrivalMessage.parseFrom(bytes)
      case FeedStatus => FeedStatusMessage.parseFrom(bytes)
      case FeedStatuses => FeedStatusesMessage.parseFrom(bytes)
      case VoyageManifestStateSnapshot => VoyageManifestStateSnapshotMessage.parseFrom(bytes)
      case VoyageManifestLatestFileName => VoyageManifestLatestFileNameMessage.parseFrom(bytes)
      case VoyageManifests => VoyageManifestsMessage.parseFrom(bytes)
      case VoyageManifest => VoyageManifestMessage.parseFrom(bytes)
      case AlertSnapshot => AlertSnapshotMessage.parseFrom(bytes)
      case Alerts => Alert.parseFrom(bytes)
      case RegisteredArrival => RegisteredArrivalMessage.parseFrom(bytes)
      case RegisteredArrivals => RegisteredArrivalsMessage.parseFrom(bytes)
      case TerminalQueuesSummary => TerminalQueuesSummaryMessage.parseFrom(bytes)
      case FlightsSummary => FlightsSummaryMessage.parseFrom(bytes)
      case CrunchMinutes => CrunchMinutesMessage.parseFrom(bytes)
      case StaffMinutes => StaffMinutesMessage.parseFrom(bytes)
      case PaxCount => PaxCountMessage.parseFrom(bytes)
      case OriginTerminalPaxCounts => OriginTerminalPaxCountsMessage.parseFrom(bytes)
      case OriginTerminalPaxCountsMgs => OriginTerminalPaxCountsMessages.parseFrom(bytes)
      case Days => DaysMessage.parseFrom(bytes)
      case RemoveDay => RemoveDayMessage.parseFrom(bytes)
      case FlightsWithSplits => FlightsWithSplitsMessage.parseFrom(bytes)
      case FlightsWithSplitsDiff => FlightsWithSplitsDiffMessage.parseFrom(bytes)
      case CrunchRequest => CrunchRequestMessage.parseFrom(bytes)
      case CrunchRequests => CrunchRequestsMessage.parseFrom(bytes)
      case RemoveCrunchRequest => RemoveCrunchRequestMessage.parseFrom(bytes)
      case SetRedListUpdate => SetRedListUpdateMessage.parseFrom(bytes)
      case RedListUpdates => RedListUpdatesMessage.parseFrom(bytes)
      case RedListUpdate => RedListUpdateMessage.parseFrom(bytes)
      case Addition => AdditionMessage.parseFrom(bytes)
      case Removal => RemovalMessage.parseFrom(bytes)
      case RemoveUpdate => RemoveUpdateMessage.parseFrom(bytes)
    }
  }
}
