package services

import actors.FlightMessageConversion._
import actors.{GetState, RecoveryLog}
import akka.persistence._
import drt.shared.{Arrival, ArrivalHelper, SDateLike}
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.{FlightStateSnapshotMessage, FlightsDiffMessage}
import services.graphstages.Crunch

case class ArrivalsState(arrivals: Map[Int, Arrival])

class ForecastBaseArrivalsActor(now: () => SDateLike,
                                expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis) {
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
  override def persistenceId: String = s"${getClass.getName}-forecast-port"

  override val snapshotInterval = 100
  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

class LiveArrivalsActor(now: () => SDateLike,
                        expireAfterMillis: Long) extends ArrivalsActor(now, expireAfterMillis) {
  override def persistenceId: String = s"${getClass.getName}-live"

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = consumeUpdates(diffsMessage, existingState)
}

abstract class ArrivalsActor(now: () => SDateLike,
                             expireAfterMillis: Long) extends PersistentActor {
  var arrivalsState: ArrivalsState = ArrivalsState(Map())
  val snapshotInterval = 500
  val log: Logger

  override def receiveRecover: Receive = {
    case diffsMessage: FlightsDiffMessage => arrivalsState = consumeDiffsMessage(diffsMessage, arrivalsState)

    case SnapshotOffer(md, ss) =>
      log.info(RecoveryLog.snapshotOffer(md))
      ss match {
        case snMessage: FlightStateSnapshotMessage =>
          arrivalsState = arrivalsStateFromSnapshotMessage(snMessage)
          log.info(s"Recovery restored state to snapshot. ${arrivalsState.arrivals.size} arrivals")
        case u => log.info(s"Received unexpected snapshot data: $u")
      }

    case RecoveryCompleted => log.info(RecoveryLog.completed)
  }

  def consumeDiffsMessage(message: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState

  def consumeRemovals(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    log.info(s"Consuming ${diffsMessage.removals.length} removals")
    val updatedArrivals = existingState.arrivals
      .filterNot { case (id, _) => diffsMessage.removals.contains(id) }

    existingState.copy(arrivals = updatedArrivals)
  }

  def consumeUpdates(diffsMessage: FlightsDiffMessage, existingState: ArrivalsState): ArrivalsState = {
    val withoutExpired = Crunch.purgeExpired(existingState.arrivals, (a: Arrival) => a.PcpTime, now, expireAfterMillis)
    log.info(s"Consuming ${diffsMessage.updates.length} updates")
    val updatedArrivals = diffsMessage.updates
      .foldLeft(withoutExpired) {
        case (soFar, fm) =>
          val arrival = flightMessageToApiFlight(fm)
          soFar.updated(arrival.uniqueId, arrival)
      }

    existingState.copy(arrivals = updatedArrivals)
  }

  override def receiveCommand: Receive = {
    case Flights(incomingArrivals) =>
      log.info(s"Received flights")

      val newStateArrivals = mergeArrivals(incomingArrivals, arrivalsState.arrivals)

      val updatedArrivals = newStateArrivals.values.toSet -- arrivalsState.arrivals.values.toSet

      arrivalsState = ArrivalsState(newStateArrivals)

      if (updatedArrivals.isEmpty) {
        log.info(s"No updates to persist")
      } else {
        persistOrSnapshot(Set(), updatedArrivals)
      }

    case ArrivalsState(incomingArrivals) if incomingArrivals != arrivalsState.arrivals =>
      log.info(s"Received updated ArrivalsState")
      val currentKeys = arrivalsState.arrivals.keys.toSet
      val newKeys = incomingArrivals.keys.toSet
      val removalKeys = currentKeys -- newKeys
      val updatedArrivals = incomingArrivals.values.toSet -- arrivalsState.arrivals.values.toSet

      arrivalsState = ArrivalsState(incomingArrivals)

      if (removalKeys.isEmpty && updatedArrivals.isEmpty) {
        log.info(s"No removals or updates to persist")
      } else {
        persistOrSnapshot(removalKeys, updatedArrivals)
      }

    case ArrivalsState(incomingArrivals) if incomingArrivals == arrivalsState.arrivals =>
      log.info(s"Received updated ArrivalsState. No changes")

    case GetState =>
      log.info(s"Received GetState request. Sending ArrivalsState with ${arrivalsState.arrivals.size} arrivals")
      sender() ! arrivalsState

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case other =>
      log.info(s"Received unexpected message $other")
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
      log.info(s"Persisting FlightsDiff with ${diffMessage.removals.length} removals & ${diffMessage.updates.length} updates")
      context.system.eventStream.publish(dm)
      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info(s"Saving ArrivalsState snapshot")
        val snapshotMessage: FlightStateSnapshotMessage = FlightStateSnapshotMessage(arrivalsState.arrivals.values.map(apiFlightToFlightMessage).toSeq)
        saveSnapshot(snapshotMessage)
      }
    }
  }
}
