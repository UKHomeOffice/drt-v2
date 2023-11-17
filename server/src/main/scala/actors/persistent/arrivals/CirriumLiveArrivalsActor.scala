package actors.persistent.arrivals

import actors.persistent.StreamingFeedStatusUpdates
import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveBaseFeedSource}

object CirriumLiveArrivalsActor extends StreamingFeedStatusUpdates {
  val persistenceId = "actors.LiveBaseArrivalsActor-live-base"
  override val sourceType: FeedSource = LiveBaseFeedSource
}

class CirriumLiveArrivalsActor(val now: () => SDateLike,
                               expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveBaseFeedSource) {
  override def persistenceId: String = CirriumLiveArrivalsActor.persistenceId

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
