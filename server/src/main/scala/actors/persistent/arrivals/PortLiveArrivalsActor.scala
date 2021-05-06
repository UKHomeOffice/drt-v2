package actors.persistent.arrivals

import drt.shared.{LiveFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.FlightsDiffMessage


object PortLiveArrivalsActor {
  val persistenceId = "actors.LiveArrivalsActor-live"
}

class PortLiveArrivalsActor(initialSnapshotBytesThreshold: Int,
                            val now: () => SDateLike,
                            expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveFeedSource) {
  override def persistenceId: String = PortLiveArrivalsActor.persistenceId

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
