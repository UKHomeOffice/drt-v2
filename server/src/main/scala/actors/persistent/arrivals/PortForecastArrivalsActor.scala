package actors.persistent.arrivals

import actors.persistent.StreamingFeedStatusUpdates
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.ports.{FeedSource, ForecastFeedSource}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.time.SDateLike


object PortForecastArrivalsActor extends StreamingFeedStatusUpdates {
  val persistenceId = "actors.ForecastPortArrivalsActor-forecast-port"
  override val sourceType: FeedSource = ForecastFeedSource
}

class PortForecastArrivalsActor(val now: () => SDateLike,
                                expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, ForecastFeedSource) {
  override def persistenceId: String = PortForecastArrivalsActor.persistenceId

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}
