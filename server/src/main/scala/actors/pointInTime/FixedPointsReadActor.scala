package actors.pointInTime

import actors.FixedPointsMessageParser.fixedPointMessagesToFixedPointsString
import actors.{FixedPointsActor, FixedPointsState}
import akka.persistence.{Recovery, RecoveryCompleted, SnapshotOffer, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.FixedPointMessage.FixedPointsStateSnapshotMessage

class FixedPointsReadActor(pointInTime: SDateLike) extends FixedPointsActor {
  override val receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot) =>
      snapshot match {
        case FixedPointsStateSnapshotMessage(fixedPoints) =>
          state = FixedPointsState(fixedPointMessagesToFixedPointsString(fixedPoints.toList))
      }

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
