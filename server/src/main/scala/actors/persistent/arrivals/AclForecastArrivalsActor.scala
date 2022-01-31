package actors.persistent.arrivals

import drt.shared.FeedStatusSuccess
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.FlightsDiffMessage
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.AclFeedSource
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.SortedMap


object AclForecastArrivalsActor {
  val persistenceId = "actors.ForecastBaseArrivalsActor-forecast-base"
}

class AclForecastArrivalsActor(initialSnapshotBytesThreshold: Int,
                               val now: () => SDateLike,
                               expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, AclFeedSource) {
  override def persistenceId: String = AclForecastArrivalsActor.persistenceId

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = {
    consumeRemovals(diffsMessage)
    consumeUpdates(diffsMessage)
  }

  override def handleFeedSuccess(incomingArrivals: Iterable[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals (base)")
    val incomingArrivalsWithKeys = incomingArrivals.map(a => (a.unique, a)).toMap
    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incomingArrivalsWithKeys, state.arrivals)
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size)

    state = state.copy(arrivals = SortedMap[UniqueArrival, Arrival]() ++ incomingArrivalsWithKeys, maybeSourceStatuses = Option(state.addStatus(newStatus)))

    if (removals.nonEmpty || updates.nonEmpty) persistArrivalUpdates(removals, updates)
    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size))
  }
}
