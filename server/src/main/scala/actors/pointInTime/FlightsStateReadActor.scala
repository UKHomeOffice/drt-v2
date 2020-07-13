package actors.pointInTime

import actors.{FlightsStateActor, GetUpdatesSince}
import akka.actor.Actor
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.FlightsWithSplitsDiffMessage
import services.SDate
import services.crunch.deskrecs.{GetStateForDateRange, GetStateForTerminalDateRange}

trait FlightsDataLike extends Actor

class FlightsStateReadActor(now: () => SDateLike, expireAfterMillis: Int, pointInTime: MillisSinceEpoch, queues: Map[Terminal, Seq[Queue]], legacyDataCutoff: SDateLike)
  extends FlightsStateActor(now, expireAfterMillis, queues, legacyDataCutoff, 1000) with FlightsDataLike {

  override val log: Logger = LoggerFactory.getLogger(s"$getClass-${SDate(pointInTime).toISOString()}")

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
    val recovery = Recovery(fromSnapshot = criteria, replayMax = 10000)
    log.info(s"Recovery: $recovery")
    recovery
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff@FlightsWithSplitsDiffMessage(Some(createdAt), _, _) if createdAt <= pointInTime =>
      handleDiffMessage(diff)
    case newerMsg: FlightsWithSplitsDiffMessage =>
      log.info(s"Ignoring FlightsWithSplitsDiffMessage created at: ${SDate(newerMsg.createdAt.getOrElse(0L)).toISOString()}")
    case other =>
      log.info(s"Got other message: ${other.getClass}")
  }

  override def receiveCommand: Receive = {
    case GetStateForDateRange(startMillis, endMillis) =>
      log.debug(s"Received GetStateForDateRange request from ${SDate(startMillis).toISOString()} to ${SDate(endMillis).toISOString()}")
      sender() ! state.window(startMillis, endMillis)

    case GetStateForTerminalDateRange(startMillis, endMillis, terminal) =>
      log.debug(s"Received GetStateForTerminalDateRange Request from ${SDate(startMillis).toISOString()} to ${SDate(endMillis).toISOString()} for $terminal")
      sender() ! state.forTerminal(terminal).window(startMillis, endMillis)

    case GetUpdatesSince(sinceMillis, startMillis, endMillis) =>
      sender() ! state.window(startMillis, endMillis).updatedSince(sinceMillis)

    case unexpected => log.error(s"Received unexpected message $unexpected")
  }
}
