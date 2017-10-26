package services

import actors.FlightMessageConversion._
import actors.GetState
import akka.actor.ActorLogging
import akka.persistence._
import drt.shared.Arrival
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.{FlightMessage, FlightStateSnapshotMessage, FlightsDiffMessage}

case class ArrivalsState(arrivals: Map[Int, Arrival])

class ForecastBaseArrivalsActor extends ArrivalsActor {
  override def persistenceId: String = s"${getClass.getName}-forecast-base"
  val log: Logger = LoggerFactory.getLogger(getClass)
}

class LiveArrivalsActor extends ArrivalsActor {
  override def persistenceId: String = s"${getClass.getName}-live"
  val log: Logger = LoggerFactory.getLogger(getClass)
}

abstract class ArrivalsActor extends PersistentActor {
  var arrivalsState: ArrivalsState = ArrivalsState(Map())
  val snapshotInterval = 500
  val log: Logger

  override def receiveRecover: Receive = {
    case diffsMessage: FlightsDiffMessage =>
      log.info(s"Recovery updating ${diffsMessage.removals.length} removals, ${diffsMessage.updates.length} updates")
      val withRemovals = arrivalsState.arrivals.filterNot {
        case (id, _) => diffsMessage.removals.contains(id)
      }
      val withUpdates = diffsMessage.updates.foldLeft(withRemovals) {
        case (soFar, fm) =>
          val arrival = flightMessageToApiFlight(fm)
          soFar.updated(arrival.uniqueId, arrival)
      }
      arrivalsState = ArrivalsState(withUpdates)

    case SnapshotOffer(md, ss) =>
      log.info(s"Recovery received SnapshotOffer($md)")
      ss match {
        case snMessage: FlightStateSnapshotMessage =>
          arrivalsState = arrivalsStateFromSnapshotMessage(snMessage)
          log.info(s"Recovery restored state to snapshot. ${arrivalsState.arrivals.size} arrivals")
        case u => log.info(s"Received unexpected snapshot data: $u")
      }

    case RecoveryCompleted =>
      log.info(s"Recovery completed")
  }

  override def receiveCommand: Receive = {
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
