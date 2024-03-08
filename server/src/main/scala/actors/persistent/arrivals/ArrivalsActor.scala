package actors.persistent.arrivals

import actors.PartitionedPortStateActor.GetFlights
import actors.persistent.staffing.GetFeedStatuses
import akka.actor.ActorRef
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import drt.shared.FlightsApi.Flights
import scalapb.GeneratedMessage
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.commands.Commands.{AddUpdatesSubscriber, GetState}
import uk.gov.homeoffice.drt.actor.commands.MergeArrivalsRequest
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike, Sizes}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, ArrivalsRestorer, UniqueArrival}
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStatus, FeedStatusFailure, FeedStatusSuccess}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.collection.immutable.SortedMap


abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Int,
                             feedSource: FeedSource,
                            ) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {

  val restorer = new ArrivalsRestorer[Arrival]
  var state: ArrivalsState = initialState
  var maybeSubscriber: Option[ActorRef] = None

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

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

    case ArrivalsFeedFailure(message, createdAt) =>
      handleFeedFailure(message, createdAt)

    case AddUpdatesSubscriber(newSubscriber) =>
      maybeSubscriber = Option(newSubscriber)

    case GetState =>
      sender() ! state

    case GetFlights(from, to) =>
      sender() ! state.arrivals.filter { case (ua, _) =>
        ua.scheduled >= from && ua.scheduled <= to
      }

    case GetFeedStatuses =>
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
    log.info(s"Received ${incomingArrivals.size} arrivals ${state.feedSource.displayName}")

    val (diff, newStatus, newState) = processIncoming(incomingArrivals, createdAt)

    state = newState

    maybeSubscriber.foreach {
      val daysFromUpdates = diff.toUpdate.values.map(a => MergeArrivalsRequest(SDate(a.Scheduled).toUtcDate)).toSet
      val daysFromRemovals = diff.toRemove.map(ua => MergeArrivalsRequest(SDate(ua.scheduled).toUtcDate)).toSet
      val updatedDays = daysFromUpdates ++ daysFromRemovals
      _ ! updatedDays
    }

    if (diff.toUpdate.nonEmpty || diff.toRemove.nonEmpty) persistArrivalUpdates(diff)
    persistFeedStatus(newStatus)
  }

  protected def processIncoming(incomingArrivals: Iterable[Arrival],
                                createdAt: SDateLike,
                               ): (ArrivalsDiff, FeedStatusSuccess, ArrivalsState) = {
    val updatedArrivals = incomingArrivals
      .map(a => a.unique -> a).toMap
      .filterNot {
        case (ua, a) => state.arrivals.get(ua).exists(_.isEqualTo(a))
      }
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size)
    val newState = state ++ (incomingArrivals, Option(state.addStatus(newStatus)))

    (ArrivalsDiff(updatedArrivals, Seq()), newStatus, newState)
  }

  def persistArrivalUpdates(arrivalsDiff: ArrivalsDiff): Unit = {
    persistAndMaybeSnapshot(FlightMessageConversion.arrivalsDiffToMessage(arrivalsDiff, now().millisSinceEpoch))
  }

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))
}
