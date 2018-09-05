package actors

import actors.FlightMessageConversion._
import akka.persistence._
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import services.SDate
import services.graphstages.Crunch

trait FeedStateLike {
  val feedName: String
  val maybeFeedStatuses: Option[FeedStatuses]

  def addStatus(newStatus: FeedStatus): FeedStatuses = {
    maybeFeedStatuses match {
      case Some(feedStatuses) => feedStatuses.add(newStatus)
      case None => FeedStatuses(feedName, List(), None, None, None).add(newStatus)
    }
  }
}

case class ArrivalsState(arrivals: Map[Int, Arrival], feedName: String, maybeFeedStatuses: Option[FeedStatuses]) extends FeedStateLike

class ForecastBaseArrivalsActor(val snapshotBytesThreshold: Int,
                                now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, "ACL forecast") {
  override def persistenceId: String = s"${getClass.getName}-forecast-base"

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    val withRemovals = consumeRemovals(diffsMessage, existingState)
    val withRemovalsAndUpdates = consumeUpdates(diffsMessage, withRemovals)

    withRemovalsAndUpdates
  }

  override def handleFeedSuccess(incomingArrivals: Seq[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals (base)")
    val incomingArrivalsWithKeys = incomingArrivals.map(a => (a.uniqueId, a)).toMap
    val (removals: Set[Int], updates: Set[Arrival]) = removalsAndUpdates(incomingArrivalsWithKeys, state.arrivals)
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size)

    state = state.copy(arrivals = incomingArrivalsWithKeys, maybeFeedStatuses = Option(state.addStatus(newStatus)))

    if (removals.nonEmpty || updates.nonEmpty) persistArrivalUpdates(removals, updates)
    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size))
  }

  def removalsAndUpdates(incomingArrivalsWithKeys: Map[Int, Arrival], existingArrivalsWithKeys: Map[Int, Arrival]): (Set[Int], Set[Arrival]) = {
    val currentKeys = existingArrivalsWithKeys.keys.toSet
    val newKeys = incomingArrivalsWithKeys.keys.toSet
    val removalKeys = currentKeys -- newKeys
    val updatedArrivals = incomingArrivalsWithKeys.values.toSet -- existingArrivalsWithKeys.values.toSet
    (removalKeys, updatedArrivals)
  }
}

class ForecastPortArrivalsActor(val snapshotBytesThreshold: Int,
                                now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, "Port forecast") {
  override def persistenceId: String = s"${getClass.getName}-forecast-port"

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage,
                          existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

class LiveArrivalsActor(val snapshotBytesThreshold: Int,
                        now: () => SDateLike,
                        expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, "Port live") {
  override def persistenceId: String = s"${getClass.getName}-live"

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage,
                          existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Long,
                             name: String) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {

  var state: ArrivalsState = initialState

  override def initialState = ArrivalsState(Map(), name, None)

  val snapshotInterval = 500

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case stateMessage: FlightStateSnapshotMessage =>
      state = arrivalsStateFromSnapshotMessage(stateMessage, name)
      logRecoveryMessage(s"restored state to snapshot. ${state.arrivals.size} arrivals")
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsDiffMessage =>
      state = consumeDiffsMessage(diff, state)
      bytesSinceSnapshotCounter += diff.serializedSize

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state = state.copy(maybeFeedStatuses = Option(state.addStatus(status)))
  }

  override def postRecoveryComplete(): Unit = {
    val withoutExpired = Crunch.purgeExpired(state.arrivals, (a: Arrival) => a.Scheduled, now, expireAfterMillis)
    state = state.copy(arrivals = withoutExpired)

    log.info(s"Recovered ${state.arrivals.size} arrivals for ${state.feedName}")
    super.postRecoveryComplete()
  }

  override def stateToMessage: GeneratedMessage = arrivalsStateToSnapshotMessage(state)

  def consumeDiffsMessage(message: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState

  def consumeRemovals(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    logRecoveryMessage(s"Consuming ${diffsMessage.removals.length} removals")
    val updatedArrivals = existingState.arrivals
      .filterNot { case (id, _) => diffsMessage.removals.contains(id) }

    existingState.copy(arrivals = updatedArrivals)
  }

  def consumeUpdates(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    logRecoveryMessage(s"Consuming ${diffsMessage.updates.length} updates")
    val updatedArrivals = diffsMessage.updates
      .foldLeft(existingState.arrivals) {
        case (soFar, fm) =>
          val arrival = flightMessageToApiFlight(fm)
          soFar.updated(arrival.uniqueId, arrival)
      }

    existingState.copy(arrivals = updatedArrivals)
  }

  override def receiveCommand: Receive = {
    case ArrivalsFeedSuccess(Flights(incomingArrivals), createdAt) => handleFeedSuccess(incomingArrivals, createdAt)

    case ArrivalsFeedFailure(message, createdAt) => handleFeedFailure(message, createdAt)

    case GetState =>
      log.info(s"Received GetState request. Sending ArrivalsState with ${state.arrivals.size} arrivals")
      sender() ! state

    case GetFeedStatuses =>
      log.info(s"Received GetFeedStatuses request")
      sender() ! state.maybeFeedStatuses

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case other =>
      log.info(s"Received unexpected message ${other.getClass}")
  }

  def handleFeedFailure(message: String, createdAt: SDateLike): Unit = {
    log.warn("Received feed failure")
    val newStatus = FeedStatusFailure(createdAt.millisSinceEpoch, message)
    state = state.copy(maybeFeedStatuses = Option(state.addStatus(newStatus)))
    persistFeedStatus(FeedStatusFailure(createdAt.millisSinceEpoch, message))
  }

  def handleFeedSuccess(incomingArrivals: Seq[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals")

    val newStateArrivals = mergeArrivals(incomingArrivals, state.arrivals)
    val updatedArrivals = newStateArrivals.values.toSet -- state.arrivals.values.toSet
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size)

    state = state.copy(arrivals = newStateArrivals, maybeFeedStatuses = Option(state.addStatus(newStatus)))

    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size))
    if (updatedArrivals.nonEmpty) persistArrivalUpdates(Set(), updatedArrivals)
  }

  def mergeArrivals(incomingArrivals: Seq[Arrival], existingArrivals: Map[Int, Arrival]): Map[Int, Arrival] = {
    incomingArrivals.foldLeft(existingArrivals) {
      case (soFar, updatedArrival) => soFar.updated(updatedArrival.uniqueId, updatedArrival)
    }
  }

  def persistArrivalUpdates(removalKeys: Set[Int], updatedArrivals: Set[Arrival]): Unit = {
    val updateMessages = updatedArrivals.map(apiFlightToFlightMessage).toSeq
    val diffMessage = FlightsDiffMessage(Option(SDate.now().millisSinceEpoch), removalKeys.toSeq, updateMessages)

    persistAndMaybeSnapshot(diffMessage)
  }

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))
}
