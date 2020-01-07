package actors

import actors.FlightMessageConversion._
import actors.acking.AckingReceiver.StreamCompleted
import actors.restore.RestorerWithLegacy
import akka.persistence._
import scalapb.GeneratedMessage
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds._
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage, UniqueArrivalMessage}
import services.SDate
import services.graphstages.Crunch

import scala.collection.mutable

trait FeedStateLike {
  def feedSource: FeedSource

  def maybeFeedStatuses: Option[FeedStatuses]

  def addStatus(newStatus: FeedStatus): FeedStatuses = {
    maybeFeedStatuses match {
      case Some(feedStatuses) => feedStatuses.add(newStatus)
      case None => FeedStatuses(feedSource, List(), None, None, None).add(newStatus)
    }
  }
}

case class ArrivalsState(arrivals: mutable.SortedMap[UniqueArrival, Arrival], var feedSource: FeedSource, var maybeFeedStatuses: Option[FeedStatuses]) extends FeedStateLike {
  def clear(): Unit = {
    arrivals.clear()
    maybeFeedStatuses = None
  }
}

class ForecastBaseArrivalsActor(initialSnapshotBytesThreshold: Int,
                                now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, AclFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-forecast-base"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = {
    consumeRemovals(diffsMessage)
    consumeUpdates(diffsMessage)
  }

  override def handleFeedSuccess(incomingArrivals: Seq[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals (base)")
    val incomingArrivalsWithKeys = incomingArrivals.map(a => (a.unique, a)).toMap
    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incomingArrivalsWithKeys, state.arrivals)
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size)

    state.arrivals.clear()
    state.arrivals ++= incomingArrivalsWithKeys
    state.maybeFeedStatuses = Option(state.addStatus(newStatus))

    if (removals.nonEmpty || updates.nonEmpty) persistArrivalUpdates(removals, updates)
    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size))
  }
}

class ForecastPortArrivalsActor(initialSnapshotBytesThreshold: Int,
                                now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, ForecastFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-forecast-port"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(100)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}

class LiveBaseArrivalsActor(initialSnapshotBytesThreshold: Int,
                            now: () => SDateLike,
                            expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, LiveBaseFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-live-base"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}

class LiveArrivalsActor(initialSnapshotBytesThreshold: Int,
                        now: () => SDateLike,
                        expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis, LiveFeedSource) {
  override def persistenceId: String = s"${getClass.getName}-live"

  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)
}

abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Long,
                             name: FeedSource) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {

  val restorer = new RestorerWithLegacy[Int, UniqueArrival, Arrival]
  val state: ArrivalsState = initialState

  override def initialState = ArrivalsState(mutable.SortedMap(), name, None)

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case stateMessage: FlightStateSnapshotMessage =>
      state.maybeFeedStatuses = feedStatusesFromSnapshotMessage(stateMessage)
      restoreArrivalsFromSnapshot(restorer, stateMessage)
      logRecoveryMessage(s"restored state to snapshot. ${state.arrivals.size} arrivals")
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsDiffMessage =>
      consumeDiffsMessage(diff)
      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state.maybeFeedStatuses = Option(state.addStatus(status))
  }

  override def postRecoveryComplete(): Unit = {
    restorer.finish()
    state.arrivals ++= restorer.items
    restorer.clear()

    Crunch.purgeExpired(state.arrivals, UniqueArrival.atTime, now, expireAfterMillis.toInt)

    log.info(s"Recovered ${state.arrivals.size} arrivals for ${state.feedSource}")
    super.postRecoveryComplete()
  }

  override def stateToMessage: GeneratedMessage = arrivalsStateToSnapshotMessage(state)

  def consumeDiffsMessage(message: FlightsDiffMessage): Unit

  def consumeRemovals(diffsMessage: FlightsDiffMessage): Unit = {
    logRecoveryMessage(s"Consuming ${diffsMessage.removals.length} removals")
    restorer.removeLegacies(diffsMessage.removalsOLD)
    restorer.remove(diffsMessage.removals.map(uam =>
      UniqueArrival(uam.getNumber, uam.getTerminalName, uam.getScheduled)
    ))
  }

  def consumeUpdates(diffsMessage: FlightsDiffMessage): Unit = {
    logRecoveryMessage(s"Consuming ${diffsMessage.updates.length} updates")
    restorer.update(diffsMessage.updates.map(flightMessageToApiFlight))
  }

  override def receiveCommand: Receive = {
    case ArrivalsFeedSuccess(Flights(incomingArrivals), createdAt) =>
      handleFeedSuccess(incomingArrivals, createdAt)
      sender() ! ArrivalsFeedSuccessAck

    case ArrivalsFeedFailure(message, createdAt) => handleFeedFailure(message, createdAt)

    case GetState =>
      log.debug(s"Received GetState request. Sending ArrivalsState with ${state.arrivals.size} arrivals")
      sender() ! state

    case GetFeedStatuses =>
      log.debug(s"Received GetFeedStatuses request")
      sender() ! state.maybeFeedStatuses

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
    state.maybeFeedStatuses = Option(state.addStatus(newStatus))
    persistFeedStatus(FeedStatusFailure(createdAt.millisSinceEpoch, message))
  }

  def handleFeedSuccess(incomingArrivals: Seq[Arrival], createdAt: SDateLike): Unit = {
    log.info(s"Received arrivals")

    state.arrivals ++= incomingArrivals.map(a => (a.unique, a))
    val updatedArrivals = incomingArrivals.toSet
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size)
    state.maybeFeedStatuses = Option(state.addStatus(newStatus))

    persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size))
    if (updatedArrivals.nonEmpty) persistArrivalUpdates(mutable.Set(), mutable.Set[Arrival]() ++ updatedArrivals)
  }

  def persistArrivalUpdates(removals: mutable.Set[UniqueArrival], updatedArrivals: mutable.Set[Arrival]): Unit = {
    val updateMessages = updatedArrivals.map(apiFlightToFlightMessage).toSeq
    val removalMessages = removals.map(ua => UniqueArrivalMessage(Option(ua.number), Option(ua.terminal.toString), Option(ua.scheduled))).toSeq
    val diffMessage = FlightsDiffMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      removals = removalMessages,
      updates = updateMessages)

    persistAndMaybeSnapshot(diffMessage)
  }

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))
}
