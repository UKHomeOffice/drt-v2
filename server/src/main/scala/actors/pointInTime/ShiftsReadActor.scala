package actors.pointInTime

import actors.ShiftsActorBase
import actors.ShiftsMessageParser.shiftMessagesToStaffAssignments
import akka.actor.Props
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.{SDateLike, ShiftAssignments}
import server.protobuf.messages.ShiftMessage.{ShiftStateSnapshotMessage, ShiftsMessage}

object ShiftsReadActor {
  def props(pointInTime: SDateLike, expireBefore: () => SDateLike): Props = Props(new ShiftsReadActor(pointInTime, expireBefore))
}

class ShiftsReadActor(pointInTime: SDateLike, expireBefore: () => SDateLike) extends ShiftsActorBase(() => pointInTime, expireBefore) {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = shiftMessagesToStaffAssignments(snapshot.shifts)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case ShiftsMessage(shiftMessages, Some(createdAtMillis)) =>
      if (createdAtMillis <= pointInTime.millisSinceEpoch) {
        logRecoveryMessage(s"ShiftsMessage received with ${shiftMessages.length} shifts")
        val shiftsToRecover = shiftMessagesToStaffAssignments(shiftMessages)
        val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToRecover.assignments)
        purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))
      }
  }

  override def postRecoveryComplete(): Unit = logPointInTimeCompleted(pointInTime)

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(fromSnapshot = criteria, replayMax = snapshotInterval)
    log.info(s"Recovery: $recovery")
    recovery
  }
}
