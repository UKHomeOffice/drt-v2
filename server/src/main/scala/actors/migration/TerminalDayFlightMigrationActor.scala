package actors.migration

import actors.PortStateMessageConversion.flightsFromMessages
import actors.acking.AckingReceiver.Ack
import actors.{FlightMessageConversion, PostgresTables, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.{SaveSnapshotSuccess, SnapshotMetadata}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.dates.UtcDate
import drt.shared.{SDateLike, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{FlightWithSplitsMessage, FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate
import slickdb.AkkaPersistenceSnapshotTable

import scala.concurrent.{ExecutionContextExecutor, Future}

object TerminalDayFlightMigrationActor {
  val snapshotTable: AkkaPersistenceSnapshotTable = AkkaPersistenceSnapshotTable(PostgresTables)

  def props(terminal: String, date: UtcDate): Props =
    Props(new TerminalDayFlightMigrationActor(date.year, date.month, date.day, terminal, snapshotTable))

  case class RemoveSnapshotUpdate(sequenceNumber: Long)

}

class TerminalDayFlightMigrationActor(
                                       year: Int,
                                       month: Int,
                                       day: Int,
                                       terminal: String,
                                       snapshotTable: AkkaPersistenceSnapshotTable
                                     ) extends RecoveryActorLike {


  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val now: () => SDateLike = () => SDate.now()

  val updateSnapshotDate: (String, MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch) => Future[Int] =
    LegacyStreamingJournalMigrationActor.updateSnapshotDateForTable(snapshotTable)

  val firstMinuteOfDay: SDateLike = SDate(year, month, day, 0, 0)
  val lastMinuteOfDay: SDateLike = firstMinuteOfDay.addDays(1).addMinutes(-1)

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-${terminal.toLowerCase}-$year%04d-$month%02d-$day%02d")

  var state: FlightsWithSplits = FlightsWithSplits.empty
  var createdAtForSnapshot: Map[Long, MillisSinceEpoch] = Map()

  override def persistenceId: String = f"terminal-flights-${terminal.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def receiveCommand: Receive = {
    case diff: FlightsWithSplitsDiffMessage =>
      if (diff.updates.nonEmpty || diff.removals.nonEmpty) {
        createdAtForSnapshot = createdAtForSnapshot + (lastSequenceNr + 1 -> diff.createdAt.getOrElse(0L))
        handleDiffMessage(diff)
        persistAndMaybeSnapshotWithAck(diff, Option((sender(), Ack)))
      } else sender() ! Ack

    case SaveSnapshotSuccess(SnapshotMetadata(persistenceId, sequenceNr, timestamp)) =>
      log.info(s"Successfully saved snapshot")
      createdAtForSnapshot.get(sequenceNr) match {
        case Some(createdAt) =>
          createdAtForSnapshot = createdAtForSnapshot - sequenceNr
          updateSnapshotDate(persistenceId, sequenceNr, timestamp, createdAt)
            .onComplete(_ => ackIfRequired())
      }

    case m => log.warn(s"Got unexpected message: $m")
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
