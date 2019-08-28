package actors

import actors.FlightMessageConversion._
import akka.persistence._
import scalapb.GeneratedMessage
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds._
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import services.SDate
import services.graphstages.Crunch

import scala.collection.mutable

trait FeedStateLike {
  def feedName: String
  def maybeFeedStatuses: Option[FeedStatuses]

  def addStatus(newStatus: FeedStatus): FeedStatuses = {
    maybeFeedStatuses match {
      case Some(feedStatuses) => feedStatuses.add(newStatus)
      case None => FeedStatuses(feedName, List(), None, None, None).add(newStatus)
    }
  }
}

case class ArrivalsState(arrivals: mutable.Map[Int, Arrival], var feedName: String, var maybeFeedStatuses: Option[FeedStatuses]) extends FeedStateLike {
  def clear(): Unit = {
    arrivals.clear()
    maybeFeedStatuses = None
  }
}

class ForecastBaseArrivalsActor(initialSnapshotBytesThreshold: Int,
                                now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, "ACL forecast") {
  override def persistenceId: String = s"${getClass.getName}-forecast-base"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

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

    state.arrivals.clear()
    state.arrivals ++= incomingArrivalsWithKeys
    state.maybeFeedStatuses = Option(state.addStatus(newStatus))

    if (removals.nonEmpty || updates.nonEmpty) persistArrivalUpdates(removals, updates)
    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size))
  }

  def removalsAndUpdates(incomingArrivalsWithKeys: Map[Int, Arrival], existingArrivalsWithKeys: mutable.Map[Int, Arrival]): (Set[Int], Set[Arrival]) = {
    val currentKeys = existingArrivalsWithKeys.keys.toSet
    val newKeys = incomingArrivalsWithKeys.keys.toSet
    val removalKeys = currentKeys -- newKeys
    val updatedArrivals = incomingArrivalsWithKeys.values.toSet -- existingArrivalsWithKeys.values.toSet
    (removalKeys, updatedArrivals)
  }
}

class ForecastPortArrivalsActor(initialSnapshotBytesThreshold: Int,
                                now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, "Port forecast") {
  override def persistenceId: String = s"${getClass.getName}-forecast-port"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage,
                          existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

class LiveArrivalsActor(initialSnapshotBytesThreshold: Int,
                        now: () => SDateLike,
                        expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, "Port live") {
  override def persistenceId: String = s"${getClass.getName}-live"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage,
                          existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Long,
                             name: String) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {

  val state: ArrivalsState = initialState

  override def initialState = ArrivalsState(mutable.Map(), name, None)

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case stateMessage: FlightStateSnapshotMessage =>
      arrivalsStateFromSnapshotMessage(stateMessage, state, name)
      logRecoveryMessage(s"restored state to snapshot. ${state.arrivals.size} arrivals")
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsDiffMessage =>
      consumeDiffsMessage(diff, state)
      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state.maybeFeedStatuses = Option(state.addStatus(status))
  }

  override def postRecoveryComplete(): Unit = {
    Crunch.purgeExpired(state.arrivals, (a: Arrival) => a.Scheduled, now, expireAfterMillis)

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
    case ArrivalsFeedSuccess(Flights(incomingArrivals), createdAt) =>
      handleFeedSuccess(incomingArrivals, createdAt)
      sender() ! ArrivalsFeedSuccessAck

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
    state.maybeFeedStatuses = Option(state.addStatus(newStatus))
    persistFeedStatus(FeedStatusFailure(createdAt.millisSinceEpoch, message))
  }

  def handleFeedSuccess(incomingArrivals: Seq[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals")

    mergeArrivals(incomingArrivals, state.arrivals)
    val updatedArrivals = incomingArrivals.toSet
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size)
    state.maybeFeedStatuses = Option(state.addStatus(newStatus))

    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size))
    if (updatedArrivals.nonEmpty) persistArrivalUpdates(Set(), updatedArrivals)
  }

  def mergeArrivals(incomingArrivals: Seq[Arrival], existingArrivals: mutable.Map[Int, Arrival]): Unit = {
    incomingArrivals.foreach(updatedArrival => existingArrivals += (updatedArrival.uniqueId -> updatedArrival))
  }

  def persistArrivalUpdates(removalKeys: Set[Int], updatedArrivals: Set[Arrival]): Unit = {
    val updateMessages = updatedArrivals.map(apiFlightToFlightMessage).toSeq
    val diffMessage = FlightsDiffMessage(Option(SDate.now().millisSinceEpoch), removalKeys.toSeq, updateMessages)

    persistAndMaybeSnapshot(diffMessage)
  }

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))
}
