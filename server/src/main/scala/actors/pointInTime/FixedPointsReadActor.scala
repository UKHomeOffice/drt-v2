package actors.pointInTime

import actors.FixedPointsMessageParser.fixedPointMessagesToFixedPoints
import actors.{FixedPointsActorBase, FixedPointsState}
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.FixedPointMessage.FixedPointsStateSnapshotMessage

class FixedPointsReadActor(now: () => SDateLike, pointInTime: SDateLike) extends FixedPointsActorBase(now) {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: FixedPointsStateSnapshotMessage => state = FixedPointsState(fixedPointMessagesToFixedPoints(snapshot.fixedPoints.toList))
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
