package actors

import actors.acking.AckingReceiver.StreamCompleted
import actors.serializers.FlightMessageConversion._
import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.feeds._
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage, UniqueArrivalMessage}
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable.SortedMap

trait FeedStateLike {
  def feedSource: FeedSource

  def maybeSourceStatuses: Option[FeedSourceStatuses]

  def addStatus(newStatus: FeedStatus): FeedSourceStatuses = {
    maybeSourceStatuses match {
      case Some(feedSourceStatuses) => feedSourceStatuses.copy(
        feedStatuses = feedSourceStatuses.feedStatuses.add(newStatus)
      )
      case None => FeedSourceStatuses(feedSource, FeedStatuses(List(), None, None, None).add(newStatus))
    }
  }
}

case class ArrivalsState(arrivals: SortedMap[UniqueArrival, Arrival],
                         feedSource: FeedSource,
                         maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike {
  def clear(): ArrivalsState = {
    copy(arrivals = SortedMap(), maybeSourceStatuses = None)
  }
}

class ForecastBaseArrivalsActor(initialSnapshotBytesThreshold: Int,
                                val now: () => SDateLike,
                                expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, AclFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-forecast-base"

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

class ForecastPortArrivalsActor(initialSnapshotBytesThreshold: Int,
                                val now: () => SDateLike,
                                expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, ForecastFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-forecast-port"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}

class LiveBaseArrivalsActor(initialSnapshotBytesThreshold: Int,
                            val now: () => SDateLike,
                            expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveBaseFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-live-base"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}

class LiveArrivalsActor(initialSnapshotBytesThreshold: Int,
                        val now: () => SDateLike,
                        expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-live"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}

abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Int,
                             feedSource: FeedSource) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {

  val restorer = new ArrivalsRestorer
  var state: ArrivalsState = initialState

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def initialState: ArrivalsState = ArrivalsState(SortedMap(), feedSource, None)

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case stateMessage: FlightStateSnapshotMessage =>
      state = state.copy(maybeSourceStatuses = feedStatusesFromSnapshotMessage(stateMessage)
        .map(fs => FeedSourceStatuses(feedSource, fs)))

      restoreArrivalsFromSnapshot(restorer, stateMessage)
      logRecoveryMessage(s"restored state to snapshot. ${state.arrivals.size} arrivals")
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsDiffMessage =>
      consumeDiffsMessage(diff)

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(status)))
  }

  override def postRecoveryComplete(): Unit = {
    val arrivals = SortedMap[UniqueArrival, Arrival]() ++ restorer.arrivals
    restorer.finish()

    state = state.copy(arrivals = Crunch.purgeExpired(arrivals, UniqueArrival.atTime, now, expireAfterMillis.toInt))

    log.info(s"Recovered ${state.arrivals.size} arrivals for ${state.feedSource}")
    super.postRecoveryComplete()
  }

  override def stateToMessage: GeneratedMessage = arrivalsStateToSnapshotMessage(state)

  def consumeDiffsMessage(message: FlightsDiffMessage): Unit

  def consumeRemovals(diffsMessage: FlightsDiffMessage): Unit = {
    if (diffsMessage.removalsOLD.nonEmpty)
      restorer.removeHashLegacies(diffsMessage.removalsOLD)

    if (diffsMessage.removals.nonEmpty)
      restorer.remove(uniqueArrivalsFromMessages(diffsMessage.removals))
  }

  def consumeUpdates(diffsMessage: FlightsDiffMessage): Unit = {
    logRecoveryMessage(s"Consuming ${diffsMessage.updates.length} updates")
    restorer.update(diffsMessage.updates.map(flightMessageToApiFlight))
  }

  override def receiveCommand: Receive = {
    case ArrivalsFeedSuccess(Flights(incomingArrivals), createdAt) =>
      handleFeedSuccess(incomingArrivals, createdAt)

    case ArrivalsFeedFailure(message, createdAt) => handleFeedFailure(message, createdAt)

    case GetState =>
      log.debug(s"Received GetState request. Sending ArrivalsState with ${state.arrivals.size} arrivals")
      sender() ! state

    case GetFeedStatuses =>
      log.debug(s"Received GetFeedStatuses request")
      sender() ! state.maybeSourceStatuses

    case ua: UniqueArrival =>
      sender() ! state.arrivals.get(ua).map(a => FeedSourceArrival(feedSource, a))

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"Received unexpected message ${unexpected.getClass}")
  }

  def handleFeedFailure(message: String, createdAt: SDateLike): Unit = {
    log.warn("Received feed failure")
    val newStatus = FeedStatusFailure(createdAt.millisSinceEpoch, message)
    state = state.copy(maybeSourceStatuses = Option(state.addStatus(newStatus)))
    persistFeedStatus(FeedStatusFailure(createdAt.millisSinceEpoch, message))
  }

  def handleFeedSuccess(incomingArrivals: Iterable[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals")

    val updatedArrivals = incomingArrivals.toSet
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size)

    state = state.copy(
      arrivals = state.arrivals ++ incomingArrivals.map(a => (a.unique, a)),
      maybeSourceStatuses = Option(state.addStatus(newStatus)))

    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size))
    if (updatedArrivals.nonEmpty) persistArrivalUpdates(Set(), updatedArrivals)
  }

  def persistArrivalUpdates(removals: Set[UniqueArrival], updatedArrivals: Iterable[Arrival]): Unit = {
    val updateMessages = updatedArrivals.map(apiFlightToFlightMessage).toSeq
    val removalMessages = removals.map(uniqueArrivalToMessage).toSeq
    val diffMessage = FlightsDiffMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      removals = removalMessages,
      updates = updateMessages)

    persistAndMaybeSnapshot(diffMessage)
  }

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))
}
