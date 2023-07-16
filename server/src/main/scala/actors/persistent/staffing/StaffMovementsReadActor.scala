package actors.persistent.staffing

import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}
import uk.gov.homeoffice.drt.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}

class StaffMovementsReadActor(pointInTime: SDateLike, expireBefore: () => SDateLike) extends StaffMovementsActorBase(() => pointInTime, expireBefore) {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage => state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMovementsMessage(movements, Some(createdAt)) =>
      if (createdAt <= pointInTime.millisSinceEpoch) {
        println(s"Processing StaffMovementsMessage with createdAt ${SDate(createdAt).toISOString}")
        updateState(addToState(movements))
      }
      else println(s"Skipping StaffMovementsMessage with createdAt ${SDate(createdAt).toISOString}")

    case RemoveStaffMovementMessage(Some(uuidToRemove), Some(createdAt)) =>
      if (createdAt <= pointInTime.millisSinceEpoch) updateState(removeFromState(uuidToRemove))
  }

  override def postRecoveryComplete(): Unit = logPointInTimeCompleted(pointInTime)

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    Recovery(fromSnapshot = criteria, replayMax = snapshotInterval)
  }
}
