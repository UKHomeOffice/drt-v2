package actors.persistent.arrivals

import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.ports.ForecastFeedSource


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
