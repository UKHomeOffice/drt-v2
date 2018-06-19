package actors.pointInTime

import actors.FixedPointsMessageParser.fixedPointMessagesToFixedPointsString
import actors.{FixedPointsActorBase, FixedPointsState, RecoveryLog}
import akka.persistence.{Recovery, RecoveryCompleted, SnapshotOffer, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.FixedPointMessage.FixedPointsStateSnapshotMessage

class FixedPointsReadActor(pointInTime: SDateLike) extends FixedPointsActorBase {
  override val receiveRecover: Receive = {
    case SnapshotOffer(md, snapshot: FixedPointsStateSnapshotMessage) =>
      log.info(s"${RecoveryLog.snapshotOffer(md)} with ${snapshot.fixedPoints.length} fixed points")
      state = FixedPointsState(fixedPointMessagesToFixedPointsString(snapshot.fixedPoints.toList))

    case RecoveryCompleted => log.info(RecoveryLog.pointInTimeCompleted(pointInTime))

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
