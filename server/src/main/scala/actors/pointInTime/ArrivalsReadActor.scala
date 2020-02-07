package actors.pointInTime

import actors.ArrivalsActor
import akka.actor.Props
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.{LiveFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightsDiffMessage}

object ArrivalsReadActor {
  def props(pointInTime: SDateLike, persistenceId: String): Props = Props(
    new ArrivalsReadActor(pointInTime, persistenceId)
  )
}

class ArrivalsReadActor(pointInTime: SDateLike, persistenceIdString: String) extends ArrivalsActor(() => pointInTime, Int.MaxValue, LiveFeedSource) {
  override def persistenceId: String = persistenceIdString

  def now: () => SDateLike = () => pointInTime

  override val snapshotBytesThreshold: Int = 0

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff@FlightsDiffMessage(Some(createdMillis), _, _, _) if createdMillis <= pointInTime.millisSinceEpoch =>

      consumeDiffsMessage(diff)

    case _: FeedStatusMessage =>

  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(fromSnapshot = criteria, replayMax = maybeSnapshotInterval.getOrElse(500).toLong)

    log.info(s"Recovery: $recovery $persistenceId ${pointInTime.toISOString()}")
    recovery
  }

}
