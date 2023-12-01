package actors.persistent.arrivals

import actors.persistent.StreamingFeedStatusUpdates
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.time.SDateLike


object PortLiveArrivalsActor extends StreamingFeedStatusUpdates {
  val persistenceId = "actors.LiveArrivalsActor-live"
  override val sourceType: FeedSource = LiveFeedSource
}

class PortLiveArrivalsActor(val now: () => SDateLike,
                            expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveFeedSource) {
  override def persistenceId: String = PortLiveArrivalsActor.persistenceId

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
