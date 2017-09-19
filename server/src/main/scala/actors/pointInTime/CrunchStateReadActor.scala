package actors.pointInTime

import actors.{CrunchStateActor, GetFlights, GetPortWorkload}
import akka.persistence.{RecoveryCompleted, _}
import controllers.GetTerminalCrunch
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import server.protobuf.messages.CrunchState.CrunchDiffMessage
import services.Crunch.CrunchState

import scala.collection.immutable._

case object GetCrunchMinutes

class CrunchStateReadActor(pointInTime: SDateLike, queues: Map[TerminalName, Seq[QueueName]]) extends CrunchStateActor(queues) {
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
      log.warning(s"unexpected message: $u")
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved CrunchState Snapshot")

    case GetFlights =>
      log.info(s"Received GetFlights message")
      state match {
        case Some(CrunchState(_, _, flights, _)) =>
          log.info(s"Found ${flights.size} flights")
          sender() ! FlightsWithSplits(flights.toList)
        case None =>
          log.info(s"No CrunchState available")
          sender() ! FlightsNotReady()
      }

    case GetPortWorkload =>
      state match {
        case Some(CrunchState(_, _, _, crunchMinutes)) =>
          sender() ! portWorkload(crunchMinutes)
        case None =>
          sender() ! WorkloadsNotReady()
      }

    case GetTerminalCrunch(terminalName) =>
      state match {
        case Some(CrunchState(startMillis, _, _, crunchMinutes)) =>
          sender() ! queueCrunchResults(terminalName, startMillis, crunchMinutes)
        case _ =>
          sender() ! List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]()
      }

    case GetCrunchMinutes =>
      log.info("Sending crunch minutes")
      sender() ! state.map(_.crunchMinutes)

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
