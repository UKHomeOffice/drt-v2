package actors.persistent.arrivals

import actors.persistent.StreamingFeedStatusUpdates
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.feeds.FeedStatusSuccess
import uk.gov.homeoffice.drt.ports.{AclFeedSource, FeedSource}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.SortedMap


object AclForecastArrivalsActor extends StreamingFeedStatusUpdates {
  val persistenceId = "actors.ForecastBaseArrivalsActor-forecast-base"
  override val sourceType: FeedSource = AclFeedSource
}

class AclForecastArrivalsActor(val now: () => SDateLike,
                               expireAfterMillis: Int,
                               override val maybePointInTime: Option[Long],
                              ) extends ArrivalsActor(now, expireAfterMillis, AclFeedSource, maybePointInTime) {
  override def persistenceId: String = AclForecastArrivalsActor.persistenceId

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = {
    consumeRemovals(diffsMessage)
    consumeUpdates(diffsMessage)
  }

  override def processIncoming(incomingArrivals: Iterable[Arrival],
                              createdAt: SDateLike,
                             ): (ArrivalsDiff, FeedStatusSuccess, ArrivalsState) = {
    val incomingArrivalsWithKeys = SortedMap[UniqueArrival, Arrival]() ++ incomingArrivals.map(a => (a.unique,a)).toMap
    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incomingArrivalsWithKeys, state.arrivals)
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size)

    val newState = ArrivalsState(arrivals = incomingArrivalsWithKeys, maybeSourceStatuses = Option(state.addStatus(newStatus)), feedSource = AclFeedSource)

    (ArrivalsDiff(updates, removals), newStatus, newState)
  }
}
