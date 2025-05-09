package actors.persistent.staffing

import actors.persistent.staffing.ShiftAssignmentsMessageParser.shiftMessagesToShiftAssignments
import org.apache.pekko.actor.Props
import org.apache.pekko.persistence.{Recovery, SnapshotSelectionCriteria}
import uk.gov.homeoffice.drt.protobuf.messages.ShiftMessage.{ShiftStateSnapshotMessage, ShiftsMessage}
import uk.gov.homeoffice.drt.time.SDateLike

object ShiftAssignmentsReadActor {
  def props(persistentId: String, pointInTime: SDateLike, expireBefore: () => SDateLike): Props =
    Props(new ShiftAssignmentsReadActor(persistentId, pointInTime, expireBefore))
}

class ShiftAssignmentsReadActor(persistentId: String, pointInTime: SDateLike, expireBefore: () => SDateLike)
  extends ShiftAssignmentsActorLike(persistentId, () => pointInTime, expireBefore, LegacyShiftAssignmentsActor.snapshotInterval) {
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
