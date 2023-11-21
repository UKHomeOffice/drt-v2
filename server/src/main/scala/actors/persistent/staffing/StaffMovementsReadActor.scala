package actors.persistent.staffing

import actors.persistent.staffing.StaffMovementsActor.staffMovementMessagesToStaffMovements
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import uk.gov.homeoffice.drt.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import uk.gov.homeoffice.drt.time.SDateLike

class StaffMovementsReadActor(pointInTime: SDateLike, expireBefore: () => SDateLike, minutesToCrunch: Int)
  extends StaffMovementsActor(() => pointInTime, expireBefore, minutesToCrunch) {
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
    Recovery(fromSnapshot = criteria, replayMax = snapshotInterval)
  }
}
