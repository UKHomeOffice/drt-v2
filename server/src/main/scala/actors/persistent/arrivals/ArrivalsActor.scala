package actors.persistent.arrivals

import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.staffing.{GetFeedStatuses, GetState}
import actors.persistent.{PersistentDrtActor, RecoveryActorLike}
import actors.serializers.FlightMessageConversion._
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared._
import scalapb.GeneratedMessage
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.SDate
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.SortedMap

abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Int,
                             feedSource: FeedSource) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {

  val restorer = new ArrivalsRestorer[Arrival]
  var state: ArrivalsState = initialState

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def initialState: ArrivalsState = ArrivalsState.empty(feedSource)

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

    state = state.copy(arrivals = Crunch.purgeExpired(arrivals, UniqueArrival.atTime, now, expireAfterMillis))

    log.info(s"Recovered ${state.arrivals.size} arrivals for ${state.feedSource}")
    super.postRecoveryComplete()
  }

  override def stateToMessage: GeneratedMessage = arrivalsStateToSnapshotMessage(state)

  def consumeDiffsMessage(message: FlightsDiffMessage): Unit

  def consumeRemovals(diffsMessage: FlightsDiffMessage): Unit = {
    restorer.removeHashLegacies(diffsMessage.removalsOLD)
    restorer.remove(uniqueArrivalsFromMessages(diffsMessage.removals))
  }

  def consumeUpdates(diffsMessage: FlightsDiffMessage): Unit = {
    logRecoveryMessage(s"Consuming ${diffsMessage.updates.length} updates")
    restorer.applyUpdates(diffsMessage.updates.map(flightMessageToApiFlight))
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

    state = state ++ (incomingArrivals, Option(state.addStatus(newStatus)))

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
