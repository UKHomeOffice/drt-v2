package actors.pointInTime

import actors.ShiftsMessageParser.shiftMessagesToShiftsString
import actors.{ShiftsActorBase, ShiftsState}
import akka.persistence.{Recovery, RecoveryCompleted, SnapshotOffer, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.ShiftMessage.ShiftStateSnapshotMessage
import services.SDate

class ShiftsReadActor(pointInTime: SDateLike) extends ShiftsActorBase {
  override val receiveRecover: Receive = {
    case SnapshotOffer(md, snapshot: ShiftStateSnapshotMessage) =>
      log.info(s"Recovery: received SnapshotOffer from ${SDate(md.timestamp).toLocalDateTimeString()} with ${snapshot.shifts.length} shifts")
      state = ShiftsState(shiftMessagesToShiftsString(snapshot.shifts.toList))

    case RecoveryCompleted =>
      log.info("RecoveryCompleted")

    case u =>
      log.info(s"Recovery: received unexpected ${u.getClass}")
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(fromSnapshot = criteria, replayMax = 0)
    log.info(s"Recovery: $recovery")
    recovery
  }
}
