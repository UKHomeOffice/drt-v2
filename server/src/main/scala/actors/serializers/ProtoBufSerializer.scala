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

  val messageClasses = Map(
    CrunchDiffMessage.getClass.getName.drop(1) -> CrunchDiffMessage,
    CrunchStateSnapshotMessage.getClass.getName.drop(1) -> CrunchStateSnapshotMessage,
    CrunchMinutesMessage.getClass.getName.drop(1) -> CrunchMinutesMessage,
    FlightsWithSplitsMessage.getClass.getName.drop(1) -> FlightsWithSplitsMessage,
    FlightsWithSplitsDiffMessage.getClass.getName.drop(1) -> FlightsWithSplitsDiffMessage,
    ShiftsMessage.getClass.getName.drop(1) -> ShiftsMessage,
    ShiftStateSnapshotMessage.getClass.getName.drop(1) -> ShiftStateSnapshotMessage,
    ShiftMessage.getClass.getName.drop(1) -> ShiftMessage,
    FixedPointsMessage.getClass.getName.drop(1) -> FixedPointsMessage,
    FixedPointsStateSnapshotMessage.getClass.getName.drop(1) -> FixedPointsStateSnapshotMessage,
    FixedPointMessage.getClass.getName.drop(1) -> FixedPointMessage,
    StaffMovementsMessage.getClass.getName.drop(1) -> StaffMovementsMessage,
    StaffMovementsStateSnapshotMessage.getClass.getName.drop(1) -> StaffMovementsStateSnapshotMessage,
    StaffMovementMessage.getClass.getName.drop(1) -> StaffMovementMessage,
    RemoveStaffMovementMessage.getClass.getName.drop(1) -> RemoveStaffMovementMessage,
    FlightsDiffMessage.getClass.getName.drop(1) -> FlightsDiffMessage,
    FlightStateSnapshotMessage.getClass.getName.drop(1) -> FlightStateSnapshotMessage,
    FlightMessage.getClass.getName.drop(1) -> FlightMessage,
    FeedStatusMessage.getClass.getName.drop(1) -> FeedStatusMessage,
    FeedStatusesMessage.getClass.getName.drop(1) -> FeedStatusesMessage,
    UniqueArrivalMessage.getClass.getName.drop(1) -> UniqueArrivalMessage,
    VoyageManifestStateSnapshotMessage.getClass.getName.drop(1) -> VoyageManifestStateSnapshotMessage,
    VoyageManifestLatestFileNameMessage.getClass.getName.drop(1) -> VoyageManifestLatestFileNameMessage,
    VoyageManifestsMessage.getClass.getName.drop(1) -> VoyageManifestsMessage,
    VoyageManifestMessage.getClass.getName.drop(1) -> VoyageManifestMessage,
    Alert.getClass.getName.drop(1) -> Alert,
    AlertSnapshotMessage.getClass.getName.drop(1) -> AlertSnapshotMessage,
    RegisteredArrivalMessage.getClass.getName.drop(1) -> RegisteredArrivalMessage,
    RegisteredArrivalsMessage.getClass.getName.drop(1) -> RegisteredArrivalsMessage,
    TerminalQueuesSummaryMessage.getClass.getName.drop(1) -> TerminalQueuesSummaryMessage,
    FlightsSummaryMessage.getClass.getName.drop(1) -> FlightsSummaryMessage,
    StaffMinutesMessage.getClass.getName.drop(1) -> StaffMinutesMessage,
    PaxCountMessage.getClass.getName.drop(1) -> PaxCountMessage,
    OriginTerminalPaxCountsMessage.getClass.getName.drop(1) -> OriginTerminalPaxCountsMessage,
    OriginTerminalPaxCountsMessages.getClass.getName.drop(1) -> OriginTerminalPaxCountsMessages,
    DaysMessage.getClass.getName.drop(1) -> DaysMessage,
    RemoveDayMessage.getClass.getName.drop(1) -> RemoveDayMessage,
    CrunchRequestMessage.getClass.getName.drop(1) -> CrunchRequestMessage,
    CrunchRequestsMessage.getClass.getName.drop(1) -> CrunchRequestsMessage,
    RemoveCrunchRequestMessage.getClass.getName.drop(1) -> RemoveCrunchRequestMessage,
    SetRedListUpdateMessage.getClass.getName.drop(1) -> SetRedListUpdateMessage,
    RedListUpdatesMessage.getClass.getName.drop(1) -> RedListUpdatesMessage,
    RedListUpdateMessage.getClass.getName.drop(1) -> RedListUpdateMessage,
    AdditionMessage.getClass.getName.drop(1) -> AdditionMessage,
    RemovalMessage.getClass.getName.drop(1) -> RemovalMessage,
    RemoveUpdateMessage.getClass.getName.drop(1) -> RemoveUpdateMessage,
  )

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    messageClasses.getOrElse(manifest, throw new Exception(s"Failed to deserialise $manifest")).parseFrom(bytes)
  }

  override def toBinary(objectToSerialize: AnyRef): Array[Byte] = objectToSerialize match {
    case m: GeneratedMessage => m.toByteArray
  }

  val log: Logger = LoggerFactory.getLogger(getClass)
}
