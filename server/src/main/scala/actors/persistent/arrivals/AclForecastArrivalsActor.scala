package actors.persistent.arrivals

import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{Arrival, TotalPaxSource, UniqueArrival}
import uk.gov.homeoffice.drt.feeds.FeedStatusSuccess
import uk.gov.homeoffice.drt.ports.AclFeedSource
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.SortedMap


object AclForecastArrivalsActor {
  val persistenceId = "actors.ForecastBaseArrivalsActor-forecast-base"
}

class AclForecastArrivalsActor(val now: () => SDateLike,
                               expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, AclFeedSource) {
  override def persistenceId: String = AclForecastArrivalsActor.persistenceId

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = {
    consumeRemovals(diffsMessage)
    consumeUpdates(diffsMessage)
  }

  override def handleFeedSuccess(incomingArrivals: Iterable[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals (base)")
    val incomingArrivalsWithKeys = incomingArrivals.map(a => (a.unique,
      a.copy(TotalPax = a.TotalPax + (AclFeedSource -> a.ActPax)))).toMap
    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incomingArrivalsWithKeys, state.arrivals)
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size)

    state = state.copy(arrivals = SortedMap[UniqueArrival, Arrival]() ++ incomingArrivalsWithKeys, maybeSourceStatuses = Option(state.addStatus(newStatus)))

    if (removals.nonEmpty || updates.nonEmpty) persistArrivalUpdates(removals, updates)
    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size))
  }
}
