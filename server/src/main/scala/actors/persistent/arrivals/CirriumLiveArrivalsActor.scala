package actors.persistent.arrivals

import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.ports.LiveBaseFeedSource

object CirriumLiveArrivalsActor {
  val persistenceId = "actors.LiveBaseArrivalsActor-live-base"
}

class CirriumLiveArrivalsActor(initialSnapshotBytesThreshold: Int,
                               val now: () => SDateLike,
                               expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveBaseFeedSource) {
  override def persistenceId: String = CirriumLiveArrivalsActor.persistenceId

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
