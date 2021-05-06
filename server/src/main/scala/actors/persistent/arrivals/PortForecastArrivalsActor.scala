package actors.persistent.arrivals

import drt.shared.{ForecastFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.FlightsDiffMessage


object PortForecastArrivalsActor {
  val persistenceId = "actors.ForecastPortArrivalsActor-forecast-port"
}

class PortForecastArrivalsActor(initialSnapshotBytesThreshold: Int,
                                val now: () => SDateLike,
                                expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, ForecastFeedSource) {
  override def persistenceId: String = PortForecastArrivalsActor.persistenceId

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
