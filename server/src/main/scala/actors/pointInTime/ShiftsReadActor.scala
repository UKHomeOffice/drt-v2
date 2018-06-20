package actors.pointInTime

import actors.ShiftsMessageParser.shiftMessagesToShiftsString
import actors.{ShiftsActorBase, ShiftsState}
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.ShiftMessage.ShiftStateSnapshotMessage

class ShiftsReadActor(pointInTime: SDateLike) extends ShiftsActorBase {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = ShiftsState(shiftMessagesToShiftsString(snapshot.shifts.toList))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case m => logRecoveryMessage(s"Didn't expect a recovery message but got a ${m.getClass}")
  }

  override def postRecoveryComplete(): Unit = logPointInTimeCompleted(pointInTime)

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(fromSnapshot = criteria, replayMax = 0)
    log.info(s"Recovery: $recovery")
    recovery
  }
}
