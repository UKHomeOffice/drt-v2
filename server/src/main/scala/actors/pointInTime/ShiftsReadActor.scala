package actors.pointInTime

import actors.ShiftsMessageParser.shiftMessagesToShiftsString
import actors.{ShiftsActorBase, ShiftsState}
import akka.persistence.{Recovery, RecoveryCompleted, SnapshotOffer, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.ShiftMessage.{ShiftStateSnapshotMessage, ShiftsMessage}

class ShiftsReadActor(pointInTime: SDateLike) extends ShiftsActorBase {
  override val receiveRecover: Receive = {
    case shiftsMessage: ShiftsMessage =>
      updateState(shiftMessagesToShiftsString(shiftsMessage.shifts.toList))

    case SnapshotOffer(_, snapshot: ShiftStateSnapshotMessage) =>
      state = ShiftsState(shiftMessagesToShiftsString(snapshot.shifts.toList))

    case RecoveryCompleted =>
      log.info(s"Recovered successfully")
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
