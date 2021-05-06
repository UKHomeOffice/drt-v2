package actors.persistent.arrivals

import drt.shared.{ForecastFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.FlightsDiffMessage

class ForecastPortArrivalsActor(initialSnapshotBytesThreshold: Int,
                                val now: () => SDateLike,
                                expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, ForecastFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-forecast-port"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
