package actors.pointInTime

import actors.{CrunchStateActor, GetPortState, GetState}
import akka.persistence.{RecoveryCompleted, _}
import drt.shared.Crunch.MillisSinceEpoch
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import server.protobuf.messages.CrunchState.CrunchDiffMessage

import scala.collection.immutable._

case object GetCrunchMinutes

class CrunchStateReadActor(pointInTime: SDateLike, queues: Map[TerminalName, Seq[QueueName]]) extends CrunchStateActor("crunch-state", queues) {
  override val receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      log.info(s"Received SnapshotOffer ${metadata.timestamp} with ${snapshot.getClass}")
      setStateFromSnapshot(snapshot)

    case cdm@ CrunchDiffMessage(createdAtOption, _, _, _, _, _) =>
      createdAtOption match {
        case Some(createdAt) if createdAt <= pointInTime.millisSinceEpoch =>
          log.info(s"Applying crunch diff with createdAt ($createdAt) <= point in time requested: ${pointInTime.millisSinceEpoch}")
          val newState = stateFromDiff(cdm, state)
          state = newState
        case Some(createdAt) =>
          log.info(s"Ignoring crunch diff with createdAt ($createdAt) > point in time requested: ${pointInTime.millisSinceEpoch}")
      }

    case RecoveryCompleted =>
      log.info(s"Recovered successfully")

    case u =>
      log.warn(s"unexpected message: $u")
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved PortState Snapshot")

    case GetState =>
      sender() ! state

    case GetPortState(start: MillisSinceEpoch, end: MillisSinceEpoch) =>
      sender() ! stateForPeriod(start, end)

    case u =>
      log.warn(s"Received unexpected message $u")
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
