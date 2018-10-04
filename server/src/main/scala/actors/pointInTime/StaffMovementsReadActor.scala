package actors.pointInTime

import java.util.UUID

import actors.{StaffMovementsActorBase, StaffMovementsState}
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}

class StaffMovementsReadActor(pointInTime: SDateLike) extends StaffMovementsActorBase {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage => state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMovementsMessage(movements, Some(createdAt)) if createdAt <= pointInTime.millisSinceEpoch =>
      val updatedStaffMovements = staffMovementMessagesToStaffMovements(movements.toList)
      updateState(updatedStaffMovements)

    case RemoveStaffMovementMessage(Some(uuidToRemove), Some(createdAt)) if createdAt <= pointInTime.millisSinceEpoch  =>
      val updatedStaffMovements = state.staffMovements - Seq(UUID.fromString(uuidToRemove))
      updateState(updatedStaffMovements)
  }

  override def postRecoveryComplete(): Unit = logPointInTimeCompleted(pointInTime)

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(
      fromSnapshot = criteria,
      replayMax = snapshotInterval)
    log.info(s"recovery: $recovery")
    recovery
  }
}
