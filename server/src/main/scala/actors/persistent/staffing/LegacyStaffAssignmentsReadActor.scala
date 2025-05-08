package actors.persistent.staffing

import actors.persistent.staffing.ShiftsMessageParser.shiftMessagesToShiftAssignments
import org.apache.pekko.actor.Props
import org.apache.pekko.persistence.{Recovery, SnapshotSelectionCriteria}
import uk.gov.homeoffice.drt.protobuf.messages.ShiftMessage.{ShiftStateSnapshotMessage, ShiftsMessage}
import uk.gov.homeoffice.drt.time.SDateLike

object LegacyStaffAssignmentsReadActor {
  def props(persistentId: String, pointInTime: SDateLike, expireBefore: () => SDateLike): Props =
    Props(new LegacyStaffAssignmentsReadActor(persistentId, pointInTime, expireBefore))
}

class LegacyStaffAssignmentsReadActor(persistentId: String, pointInTime: SDateLike, expireBefore: () => SDateLike)
  extends LegacyStaffAssignmentsActor(persistentId, () => pointInTime, expireBefore, LegacyStaffAssignmentsActor.snapshotInterval) {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = shiftMessagesToShiftAssignments(snapshot.shifts)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case ShiftsMessage(shiftMessages, Some(createdAtMillis)) =>
      if (createdAtMillis <= pointInTime.millisSinceEpoch) {
        logRecoveryMessage(s"ShiftsMessage received with ${shiftMessages.length} shifts")
        val shiftsToRecover = shiftMessagesToShiftAssignments(shiftMessages)
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
