package actors.pointInTime

import actors.{CrunchStateActor, GetFlights, GetPortWorkload}
import akka.persistence._
import controllers.GetTerminalCrunch
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import server.protobuf.messages.CrunchState.CrunchStateSnapshotMessage
import services.Crunch.CrunchState

import scala.collection.immutable._

class CrunchStateReadActor(pointInTime: SDateLike, queues: Map[TerminalName, Seq[QueueName]]) extends CrunchStateActor(queues) {
  override val receiveRecover: Receive = {
    case SnapshotOffer(md, s) =>
      log.info(s"restoring crunch state $md")
      s match {
        case sm@CrunchStateSnapshotMessage(_, _, _, _) =>
          log.info("matched CrunchStateSnapshotMessage, storing it.")
          state = Option(snapshotMessageToState(sm))
        case somethingElse =>
          log.error(s"Got $somethingElse when trying to restore Crunch State")
      }

    case u =>
      log.warning(s"unexpected message: $u")
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved CrunchState Snapshot")

    case GetFlights =>
      state match {
        case Some(CrunchState(_, _, flights, _)) =>
          sender() ! FlightsWithSplits(flights.toList)
        case None => FlightsNotReady
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