package actors.persistent.arrivals

import actors.persistent.Sizes
import akka.actor.Props
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.ports.FeedSource

object ArrivalsReadActor {
  def props(pointInTime: SDateLike, persistenceId: String, feedSource: FeedSource): Props = Props(
    new ArrivalsReadActor(pointInTime, persistenceId, feedSource)
  )
}

class ArrivalsReadActor(pointInTime: SDateLike, persistenceIdString: String, feedSource: FeedSource)
  extends ArrivalsActor(() => pointInTime, Int.MaxValue, feedSource) {
  override def persistenceId: String = persistenceIdString

  def now: () => SDateLike = () => pointInTime

  override val maybeSnapshotInterval: Option[Int] = Option(1000)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff@FlightsDiffMessage(Some(createdMillis), _, _, _) =>
      if (createdMillis <= pointInTime.millisSinceEpoch) consumeDiffsMessage(diff)
    case _ =>
  }

  override def recovery: Recovery = {
    val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime.millisSinceEpoch)
    Recovery(fromSnapshot = criteria, replayMax = 10000)
  }

}
