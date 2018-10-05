package actors.pointInTime

import actors.{StaffMovementsActorBase, StaffMovementsState}
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}

class StaffMovementsReadActor(pointInTime: SDateLike, expireAfterMillis: Long) extends StaffMovementsActorBase(() => pointInTime, expireAfterMillis) {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage => state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMovementsMessage(movements, Some(createdAt)) =>
      if (createdAt <= pointInTime.millisSinceEpoch) updateState(addToState(movements))

    case RemoveStaffMovementMessage(Some(uuidToRemove), Some(createdAt)) =>
      if (createdAt <= pointInTime.millisSinceEpoch) updateState(removeFromState(uuidToRemove))
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
