package services

import actors.FlightMessageConversion._
import actors.{GetFeedStatuses, GetState, PersistentDrtActor, RecoveryActorLike}
import akka.persistence._
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import server.protobuf.messages.FlightsMessage.{FlightStateSnapshotMessage, FlightsDiffMessage}
import services.graphstages.Crunch

case class ArrivalsState(arrivals: Map[Int, Arrival], feedStatuses: FeedStatuses)

class ForecastBaseArrivalsActor(now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis) {
  val name = "ACL forecast"
  override def persistenceId: String = s"${getClass.getName}-forecast-base"

  override val snapshotInterval = 100
  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    val withRemovals = consumeRemovals(diffsMessage, existingState)
    val withRemovalsAndUpdates = consumeUpdates(diffsMessage, withRemovals)

    withRemovalsAndUpdates
  }
}

class ForecastPortArrivalsActor(now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis) {
  val name = "Port forecast"
  override def persistenceId: String = s"${getClass.getName}-forecast-port"

  override val snapshotInterval = 100
  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

class LiveArrivalsActor(now: () => SDateLike,
                        expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis) {
  val name = "Port live"
  override def persistenceId: String = s"${getClass.getName}-live"

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Long) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {

  val name: String
  var state: ArrivalsState = initialState

  override def initialState = ArrivalsState(Map(), FeedStatuses(name, List(), None, None, None))

  val snapshotInterval = 500

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case stateMessage: FlightStateSnapshotMessage =>
      state = arrivalsStateFromSnapshotMessage(stateMessage)
      logRecoveryMessage(s"restored state to snapshot. ${state.arrivals.size} arrivals")
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsDiffMessage =>
      state = consumeDiffsMessage(diff, state)
      bytesSinceSnapshotCounter += diff.serializedSize
  }

  def consumeDiffsMessage(message: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState

  def consumeRemovals(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    logRecoveryMessage(s"Consuming ${diffsMessage.removals.length} removals")
    val updatedArrivals = existingState.arrivals
      .filterNot { case (id, _) => diffsMessage.removals.contains(id) }

    existingState.copy(arrivals = updatedArrivals)
  }

  def consumeUpdates(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    val withoutExpired = Crunch.purgeExpired(existingState.arrivals, (a: Arrival) => a.PcpTime.getOrElse(0L), now, expireAfterMillis)
    logRecoveryMessage(s"Consuming ${diffsMessage.updates.length} updates")
    val updatedArrivals = diffsMessage.updates
      .foldLeft(withoutExpired) {
        case (soFar, fm) =>
          val arrival = flightMessageToApiFlight(fm)
          soFar.updated(arrival.uniqueId, arrival)
      }

    existingState.copy(arrivals = updatedArrivals)
  }

  override def receiveCommand: Receive = {
    case ArrivalsFeedSuccess(Flights(incomingArrivals), createdAt) =>
      log.info(s"Received flights")

      val newStateArrivals = mergeArrivals(incomingArrivals, state.arrivals)

      val updatedArrivals = newStateArrivals.values.toSet -- state.arrivals.values.toSet

      val statuses: FeedStatuses = state.feedStatuses.addStatus(createdAt, updatedArrivals)

      state = ArrivalsState(newStateArrivals, statuses)

      if (updatedArrivals.isEmpty) {
        log.info(s"No updates to persist")
      } else {
        persistOrSnapshot(Set(), updatedArrivals)
      }

    case ArrivalsFeedFailure(message, createdAt) =>
      val statuses: FeedStatuses = state.feedStatuses.addStatus(createdAt, message)
      state = state.copy(feedStatuses = statuses)

    case Some(ArrivalsState(incomingArrivals, _)) if incomingArrivals != state.arrivals =>
      log.info(s"Received updated ArrivalsState")
      val currentKeys = state.arrivals.keys.toSet
      val newKeys = incomingArrivals.keys.toSet
      val removalKeys = currentKeys -- newKeys
      val updatedArrivals = incomingArrivals.values.toSet -- state.arrivals.values.toSet

      state = ArrivalsState(incomingArrivals, state.feedStatuses)

      if (removalKeys.isEmpty && updatedArrivals.isEmpty) {
        log.info(s"No removals or updates to persist")
      } else {
        persistOrSnapshot(removalKeys, updatedArrivals)
      }

    case Some(ArrivalsState(incomingArrivals, _)) if incomingArrivals == state.arrivals =>
      log.info(s"Received updated ArrivalsState. No changes")

    case None =>
      log.info(s"Received None. Presumably feed connection failed")

    case GetState =>
      log.info(s"Received GetState request. Sending ArrivalsState with ${state.arrivals.size} arrivals")
      sender() ! state

    case GetFeedStatuses =>
      log.info(s"Received GetFeedStatuses request")
      sender() ! state.feedStatuses

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case other =>
      log.info(s"Received unexpected message ${other.getClass}")
  }

  def mergeArrivals(incomingArrivals: Seq[Arrival], existingArrivals: Map[Int, Arrival]): Map[Int, Arrival] = {
    incomingArrivals.foldLeft(existingArrivals) {
      case (soFar, updatedArrival) => soFar.updated(updatedArrival.uniqueId, updatedArrival)
    }
  }

  def persistOrSnapshot(removalKeys: Set[Int], updatedArrivals: Set[Arrival]): Unit = {
    val updateMessages = updatedArrivals.map(apiFlightToFlightMessage).toSeq
    val diffMessage = FlightsDiffMessage(Option(SDate.now().millisSinceEpoch), removalKeys.toSeq, updateMessages)

    persist(diffMessage) { dm =>
      val messageBytes = diffMessage.serializedSize
      log.info(s"Persisting $messageBytes bytes of FlightsDiff with ${diffMessage.removals.length} removals & ${diffMessage.updates.length} updates")
      context.system.eventStream.publish(dm)
      bytesSinceSnapshotCounter += messageBytes
      logPersistedBytesCounter(bytesSinceSnapshotCounter)

      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        val snapshotMessage: FlightStateSnapshotMessage = FlightStateSnapshotMessage(state.arrivals.values.map(apiFlightToFlightMessage).toSeq)
        saveSnapshot(snapshotMessage)
        log.info(s"Saved {${snapshotMessage.serializedSize} bytes of ArrivalsState snapshot. Reset byte counter to zero")
        bytesSinceSnapshotCounter = 0
      }
    }
  }
}
