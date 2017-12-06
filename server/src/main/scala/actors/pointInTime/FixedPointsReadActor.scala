package actors.pointInTime

import actors.FixedPointsMessageParser.fixedPointMessagesToFixedPointsString
import actors.{FixedPointsActorBase, FixedPointsState}
import akka.persistence.{Recovery, RecoveryCompleted, SnapshotOffer, SnapshotSelectionCriteria}
import drt.shared.SDateLike
import server.protobuf.messages.FixedPointMessage.FixedPointsStateSnapshotMessage
import services.SDate

class FixedPointsReadActor(pointInTime: SDateLike) extends FixedPointsActorBase {
  override val receiveRecover: Receive = {
    case SnapshotOffer(md, snapshot: FixedPointsStateSnapshotMessage) =>
      log.info(s"Recovery: received SnapshotOffer from ${SDate(md.timestamp).toLocalDateTimeString()} with ${snapshot.fixedPoints.length} fixed points")
      state = FixedPointsState(fixedPointMessagesToFixedPointsString(snapshot.fixedPoints.toList))

    case RecoveryCompleted =>
      log.info(s"Recovered successfully")

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
