package actors.persistent.staffing

import actors.persistent.staffing.FixedPointsMessageParser.fixedPointMessagesToFixedPoints
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import uk.gov.homeoffice.drt.protobuf.messages.FixedPointMessage.{FixedPointsMessage, FixedPointsStateSnapshotMessage}
import uk.gov.homeoffice.drt.time.SDateLike

class FixedPointsReadActor(pointInTime: SDateLike, val now: () => SDateLike) extends FixedPointsActorBase(() => pointInTime) {
  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: FixedPointsStateSnapshotMessage => state = fixedPointMessagesToFixedPoints(snapshot.fixedPoints.toList)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case FixedPointsMessage(fixedPointMessages, Some(createdAtMillis)) =>
      if (createdAtMillis <= pointInTime.millisSinceEpoch) {
        logRecoveryMessage(s"FixedPointsMessage received with ${fixedPointMessages.length} fixed points")
        val fixedPointsToRecover = fixedPointMessagesToFixedPoints(fixedPointMessages)
        updateState(fixedPointsToRecover)
      }
  }

  override def postRecoveryComplete(): Unit = logPointInTimeCompleted(pointInTime)

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    Recovery(fromSnapshot = criteria, replayMax = 250)
  }
}
