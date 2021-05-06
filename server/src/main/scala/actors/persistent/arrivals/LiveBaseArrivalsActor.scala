package actors.persistent.arrivals

import drt.shared.{LiveBaseFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.FlightsDiffMessage

class LiveBaseArrivalsActor(initialSnapshotBytesThreshold: Int,
                            val now: () => SDateLike,
                            expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveBaseFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-live-base"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
