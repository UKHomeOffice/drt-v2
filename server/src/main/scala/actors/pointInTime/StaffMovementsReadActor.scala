package actors.pointInTime

import actors.{RecoveryLog, StaffMovementsActorBase, StaffMovementsState}
import akka.persistence.{Recovery, RecoveryCompleted, SnapshotOffer, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.StaffMovementMessages.{StaffMovementsMessage, StaffMovementsStateSnapshotMessage}

class StaffMovementsReadActor(pointInTime: SDateLike) extends StaffMovementsActorBase {
  override val receiveRecover: Receive = {
    case smm: StaffMovementsMessage =>
      updateState(staffMovementMessagesToStaffMovements(smm.staffMovements.toList))

    case SnapshotOffer(md, snapshot: StaffMovementsStateSnapshotMessage) =>
      log.info(RecoveryLog.snapshotOffer(md))
      state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))

    case RecoveryCompleted => log.info(RecoveryLog.pointInTimeCompleted(pointInTime))
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(
      fromSnapshot = criteria,
      replayMax = snapshotInterval)
    log.info(s"recovery: $recovery")
    recovery
  }
}
