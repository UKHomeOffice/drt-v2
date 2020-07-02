package actors.pointInTime

import actors.ArrivalsActor
import akka.actor.Props
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.{FeedSource, LiveFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightsDiffMessage}

object ArrivalsReadActor {
  def props(pointInTime: SDateLike, persistenceId: String, feedSource: FeedSource): Props = Props(
    new ArrivalsReadActor(pointInTime, persistenceId, feedSource)
  )
}

class ArrivalsReadActor(pointInTime: SDateLike, persistenceIdString: String, feedSource: FeedSource) extends ArrivalsActor(() => pointInTime, Int.MaxValue, feedSource) {
  override def persistenceId: String = persistenceIdString

  def now: () => SDateLike = () => pointInTime

  override val snapshotBytesThreshold: Int = 0

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff@FlightsDiffMessage(Some(createdMillis), _, _, _) =>
      if (createdMillis <= pointInTime.millisSinceEpoch) consumeDiffsMessage(diff)
    case _ =>
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    val recovery = Recovery(fromSnapshot = criteria, replayMax = 10000)

    log.info(s"Recovery: $recovery $persistenceId ${pointInTime.toISOString()}")
    recovery
  }

}
