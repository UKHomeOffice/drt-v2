package actors.serializers

import akka.serialization.SerializerWithStringManifest
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.Alert.{Alert, AlertSnapshotMessage}
import server.protobuf.messages.CrunchState.{CrunchRequestMessage, _}
import server.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, _}
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

  val messageClasses = List(
    CrunchDiffMessage,
    CrunchStateSnapshotMessage,
    CrunchMinutesMessage,
    FlightsWithSplitsMessage,
    FlightsWithSplitsDiffMessage,
    ShiftsMessage,
    ShiftStateSnapshotMessage,
    ShiftMessage,
    FixedPointsMessage,
    FixedPointsStateSnapshotMessage,
    FixedPointMessage,
    StaffMovementsMessage,
    StaffMovementsStateSnapshotMessage,
    StaffMovementMessage,
    RemoveStaffMovementMessage,
    FlightsDiffMessage,
    FlightStateSnapshotMessage,
    FlightMessage,
    FeedStatusMessage,
    FeedStatusesMessage,
    UniqueArrivalMessage,
    VoyageManifestStateSnapshotMessage,
    VoyageManifestLatestFileNameMessage,
    VoyageManifestsMessage,
    VoyageManifestMessage,
    Alert,
    AlertSnapshotMessage,
    RegisteredArrivalMessage,
    RegisteredArrivalsMessage,
    TerminalQueuesSummaryMessage,
    FlightsSummaryMessage,
    StaffMinutesMessage,
    PaxCountMessage,
    OriginTerminalPaxCountsMessage,
    OriginTerminalPaxCountsMessages,
    DaysMessage,
    RemoveDayMessage,
    CrunchRequestMessage,
    CrunchRequestsMessage,
    RemoveCrunchRequestMessage,
    SetRedListUpdateMessage,
    RedListUpdatesMessage,
    RedListUpdateMessage,
    AdditionMessage,
    RemovalMessage,
    RemoveUpdateMessage,
  ).map(m => (m.getClass.getName.dropRight(1), m)).toMap

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    messageClasses.getOrElse(manifest, throw new Exception(s"Failed to deserialise $manifest")).parseFrom(bytes)
  }

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = objectToSerialize match {
    case m: GeneratedMessage => m.toByteArray
  }

  val log: Logger = LoggerFactory.getLogger(getClass)
}
