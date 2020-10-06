package actors.migration

import actors.PortStateMessageConversion.flightsFromMessages
import actors.acking.AckingReceiver.Ack
import actors.{FlightMessageConversion, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.{SaveSnapshotSuccess, SnapshotMetadata}
import akka.persistence.SnapshotProtocol.SaveSnapshot
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, UniqueArrival, UtcDate}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{FlightWithSplitsMessage, FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate

object TerminalDayFlightMigrationActor {
  def props(terminal: String, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayFlightMigrationActor(date.year, date.month, date.day, terminal, now))
}

class TerminalDayFlightMigrationActor(
                                       year: Int,
                                       month: Int,
                                       day: Int,
                                       terminal: String,
                                       val now: () => SDateLike
                                     ) extends RecoveryActorLike {
  val firstMinuteOfDay: SDateLike = SDate(year, month, day, 0, 0)
  val lastMinuteOfDay: SDateLike = firstMinuteOfDay.addDays(1).addMinutes(-1)

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-${terminal.toLowerCase}-$year%04d-$month%02d-$day%02d")

  var state: FlightsWithSplits = FlightsWithSplits.empty

  override def persistenceId: String = f"terminal-flights-${terminal.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def receiveCommand: Receive = {
    case diff: FlightsWithSplitsDiffMessage =>
      handleDiffMessage(diff)
      persistAndMaybeSnapshot(diff, Option((sender(), Ack)))

    case SaveSnapshotSuccess(metadata) =>


    case m => log.warn(s"Got unexpected message: $m")
  }

  def saveSnapshot(snapshot: Any): Unit = {
    snapshotStore ! SaveSnapshot(SnapshotMetadata(snapshotterId, snapshotSequenceNr), snapshot)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsWithSplitsDiffMessage => handleDiffMessage(diff)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case FlightsWithSplitsMessage(flightMessages) => setStateFromSnapshot(flightMessages)
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.values)

  def handleDiffMessage(diff: FlightsWithSplitsDiffMessage): Unit = {
    state = state -- diff.removals.map(uniqueArrivalFromMessage)
    state = state ++ flightsFromMessages(diff.updates)
    log.debug(s"Recovery: state contains ${state.flights.size} flights")
  }

  def uniqueArrivalFromMessage(uam: UniqueArrivalMessage): UniqueArrival =
    UniqueArrival(uam.getNumber, uam.getTerminalName, uam.getScheduled)

  def setStateFromSnapshot(flightMessages: Seq[FlightWithSplitsMessage]): Unit = {
    state = FlightsWithSplits(flightsFromMessages(flightMessages))
  }
}
