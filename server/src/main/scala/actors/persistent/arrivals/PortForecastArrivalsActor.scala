package actors.persistent.arrivals

import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.ForecastFeedSource
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.time.SDateLike


object PortForecastArrivalsActor {
  val persistenceId = "actors.ForecastPortArrivalsActor-forecast-port"
}

class PortForecastArrivalsActor(val now: () => SDateLike,
                                expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, ForecastFeedSource) {
  override def persistenceId: String = PortForecastArrivalsActor.persistenceId

  println(s"\n\n*** Creating PortForecastArrivalsActor\n")

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)

  override def persistAndMaybeSnapshot(message: GeneratedMessage): Unit = {
    println(s"\n\n*** persistAndMaybeSnapshot forecast stuff\n")
    persistAndMaybeSnapshotWithAck(message, List())
  }
}
