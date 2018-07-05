package services

import actors.FlightMessageConversion._
import actors.{GetFeedStatuses, GetState, PersistentDrtActor, RecoveryActorLike}
import akka.persistence._
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import server.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import services.graphstages.Crunch

case class ArrivalsState(arrivals: Map[Int, Arrival], maybeFeedStatuses: Option[FeedStatuses])

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

  override def initialState = ArrivalsState(Map(), None)

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

    case feedStatusMessage: FeedStatusMessage =>
      val statuses: FeedStatuses = state.maybeFeedStatuses match {
        case Some(feedStatuses) => feedStatuses.add(feedStatusFromFeedStatusMessage(feedStatusMessage))
        case None => FeedStatuses(name, List(), None, None, None).add(feedStatusFromFeedStatusMessage(feedStatusMessage))
      }

      state = state.copy(maybeFeedStatuses = Option(statuses))
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

      val statuses: FeedStatuses = state.maybeFeedStatuses match {
        case Some(feedStatuses) => feedStatuses.addStatus(createdAt, updatedArrivals)
        case None => FeedStatuses(name, List(), None, None, None).addStatus(createdAt, updatedArrivals)
      }

      state = state.copy(arrivals = newStateArrivals, maybeFeedStatuses = Option(statuses))

      persistFeedStatus(FeedStatusSuccess(createdAt.millisSinceEpoch, updatedArrivals.size))
      if (updatedArrivals.nonEmpty) persistArrivalUpdates(Set(), updatedArrivals)

      snapshotIfNeeded(state)


    case ArrivalsFeedFailure(message, createdAt) =>
      val statuses: FeedStatuses = state.maybeFeedStatuses match {
        case Some(feedStatuses) => feedStatuses.addStatus(createdAt, message)
        case None => FeedStatuses(name, List(), None, None, None).addStatus(createdAt, message)
      }
      state = state.copy(maybeFeedStatuses = Option(statuses))
      persistFeedStatus(FeedStatusFailure(createdAt.millisSinceEpoch, message))

    case Some(ArrivalsState(incomingArrivals, _)) if incomingArrivals != state.arrivals =>
      log.info(s"Received updated ArrivalsState")
      val currentKeys = state.arrivals.keys.toSet
      val newKeys = incomingArrivals.keys.toSet
      val removalKeys = currentKeys -- newKeys
      val updatedArrivals = incomingArrivals.values.toSet -- state.arrivals.values.toSet

      state = state.copy(arrivals = incomingArrivals, state.maybeFeedStatuses)

      if (removalKeys.nonEmpty || updatedArrivals.nonEmpty) persistArrivalUpdates(removalKeys, updatedArrivals)

      snapshotIfNeeded(state)

    case Some(ArrivalsState(incomingArrivals, _)) if incomingArrivals == state.arrivals =>
      log.info(s"Received updated ArrivalsState. No changes")

    case None =>
      log.info(s"Received None. Presumably feed connection failed")

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

  def mergeArrivals(incomingArrivals: Seq[Arrival], existingArrivals: Map[Int, Arrival]): Map[Int, Arrival] = {
    incomingArrivals.foldLeft(existingArrivals) {
      case (soFar, updatedArrival) => soFar.updated(updatedArrival.uniqueId, updatedArrival)
    }
  }

  def persistArrivalUpdates(removalKeys: Set[Int], updatedArrivals: Set[Arrival]): Unit = {
    val updateMessages = updatedArrivals.map(apiFlightToFlightMessage).toSeq
    val diffMessage = FlightsDiffMessage(Option(SDate.now().millisSinceEpoch), removalKeys.toSeq, updateMessages)

    persist(diffMessage) { dm =>
      val messageBytes = diffMessage.serializedSize
      log.info(s"Persisting $messageBytes bytes of FlightsDiff with ${diffMessage.removals.length} removals & ${diffMessage.updates.length} updates")
      context.system.eventStream.publish(dm)
      bytesSinceSnapshotCounter += messageBytes
      logPersistedBytesCounter(bytesSinceSnapshotCounter)
    }
  }

  def persistFeedStatus(feedStatus: FeedStatus): Unit = {
    val message = feedStatusToMessage(feedStatus)

    persist(message) { msg =>
      val messageBytes = message.serializedSize
      log.info(s"Persisting $messageBytes bytes of $feedStatus")
      context.system.eventStream.publish(msg)
      bytesSinceSnapshotCounter += messageBytes
      logPersistedBytesCounter(bytesSinceSnapshotCounter)
    }
  }

  def snapshotIfNeeded(stateToSnapshot: ArrivalsState): Unit = {
    if (bytesSinceSnapshotCounter > 10 * oneMegaByte) {
      val snapshotMessage: FlightStateSnapshotMessage = arrivalsStateToSnapshotMessage(stateToSnapshot)
      saveSnapshot(snapshotMessage)
      log.info(s"Saved {${snapshotMessage.serializedSize} bytes of ArrivalsState snapshot. Reset byte counter to zero")
      bytesSinceSnapshotCounter = 0
    }
  }
}
