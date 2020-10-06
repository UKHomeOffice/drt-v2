package actors.migration

import actors.PortStateMessageConversion.flightsFromMessages
import actors.acking.AckingReceiver.Ack
import actors.{FlightMessageConversion, PostgresTables, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.{Recovery, SaveSnapshotSuccess, SnapshotMetadata, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{SDateLike, UniqueArrival, UtcDate}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{FlightWithSplitsMessage, FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate
import slick.jdbc.SQLActionBuilder
import slickdb.AkkaPersistenceSnapshotTable

import scala.concurrent.ExecutionContextExecutor

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
  import snapshotTable.tables.profile.api._

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val now: () => SDateLike = () => SDate.now()

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
      createdAtForSnapshot = createdAtForSnapshot + (lastSequenceNr + 1 -> diff.createdAt.getOrElse(0L))
      persistAndMaybeSnapshot(diff, Option((sender(), Ack)))

    case SaveSnapshotSuccess(SnapshotMetadata(persistenceId, sequenceNr, timestamp)) =>
      log.info(s"Successfully saved snapshot")
      createdAtForSnapshot.get(sequenceNr) match {
        case Some(createdAt) =>
          log.info(s"Going to update the timestamp from ${SDate(timestamp).toISOString()} to ${SDate(createdAt).toISOString()} for $persistenceId / $sequenceNr")
          createdAtForSnapshot = createdAtForSnapshot - sequenceNr
          val updateQuery: SQLActionBuilder =
            sql"""UPDATE snapshot
                    SET created=$createdAt
                  WHERE persistence_id=$persistenceId
                    AND sequence_number=$sequenceNr"""
          snapshotTable.db.run(updateQuery.asUpdate).onComplete { _ =>
            maybeAckAfterSnapshot.foreach {
              case (replyTo, ackMsg) =>
                replyTo ! ackMsg
                maybeAckAfterSnapshot = None
            }
          }
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
