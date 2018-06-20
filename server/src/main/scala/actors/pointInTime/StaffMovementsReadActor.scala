package actors.pointInTime

import actors.{StaffMovementsActorBase, StaffMovementsState}
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.StaffMovementMessages.{StaffMovementsMessage, StaffMovementsStateSnapshotMessage}

class StaffMovementsReadActor(pointInTime: SDateLike) extends StaffMovementsActorBase {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage => state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case smm: StaffMovementsMessage => updateState(staffMovementMessagesToStaffMovements(smm.staffMovements.toList))
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
