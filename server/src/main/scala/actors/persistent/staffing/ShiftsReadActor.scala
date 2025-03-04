package actors.persistent.staffing

import actors.persistent.staffing.ShiftsMessageParser.shiftMessagesToStaffAssignments
import akka.actor.Props
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import uk.gov.homeoffice.drt.protobuf.messages.ShiftMessage.{ShiftStateSnapshotMessage, ShiftsMessage}
import uk.gov.homeoffice.drt.time.SDateLike

object ShiftsReadActor {
  def props(persistentId: String, pointInTime: SDateLike, expireBefore: () => SDateLike): Props =
    Props(new ShiftsReadActor(persistentId, pointInTime, expireBefore))
}

class ShiftsReadActor(persistentId: String, pointInTime: SDateLike, expireBefore: () => SDateLike)
  extends ShiftsActor(persistentId, () => pointInTime, expireBefore, ShiftsActor.snapshotInterval) {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = shiftMessagesToStaffAssignments(snapshot.shifts)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case ShiftsMessage(shiftMessages, Some(createdAtMillis)) =>
      if (createdAtMillis <= pointInTime.millisSinceEpoch) {
        logRecoveryMessage(s"ShiftsMessage received with ${shiftMessages.length} shifts")
        val shiftsToRecover = shiftMessagesToStaffAssignments(shiftMessages)
        val updatedShifts = state.applyUpdates(shiftsToRecover.assignments)
        purgeExpiredAndUpdateState(updatedShifts)
      }
  }

  override def postRecoveryComplete(): Unit = logPointInTimeCompleted(pointInTime)

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    Recovery(fromSnapshot = criteria, replayMax = snapshotInterval)
  }
}
