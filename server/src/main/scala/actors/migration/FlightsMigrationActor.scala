package actors.migration

import actors.StreamingJournalLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.migration.FlightsMigrationActor.{MigrationStatus, Processed}
import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, KillSwitches, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, FlightWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

case object StartMigration

case object GetMigrationStatus

case object StopMigration

case class FlightMessageMigration(
                                   sequenceId: Long,
                                   createdAt: MillisSinceEpoch,
                                   flightRemovalsMessage: Seq[UniqueArrivalMessage],
                                   flightsUpdateMessages: Seq[FlightWithSplitsMessage],
                                 )

object FlightsMigrationActor {
  val legacyPersistenceId = "crunch-state"

  def props(journalType: StreamingJournalLike, flightMigrationRouterActor: ActorRef): Props =
    Props(new FlightsMigrationActor(journalType, flightMigrationRouterActor))

  case class Processed(seqNr: Long, createdAt: MillisSinceEpoch, replyTo: ActorRef)

  case class MigrationStatus(seqNr: Long, createdAt: MillisSinceEpoch)

}


class FlightsMigrationActor(journalType: StreamingJournalLike, flightMigrationRouterActor: ActorRef)
  extends PersistentActor with ActorLogging {

  override val persistenceId = "crunch-state-migration"

  val legacyPersistenceId: String = FlightsMigrationActor.legacyPersistenceId

  var maybeKillSwitch: Option[UniqueKillSwitch] = None
  var state: MigrationStatus = MigrationStatus(0L, 0L)

  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = Timeout(1 second)
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val startUpdatesStream: Long => Unit = (nr: Long) => if (maybeKillSwitch.isEmpty) {
    log.info(s"Starting event stream from seq no $nr")
    val (_, killSwitch) = PersistenceQuery(context.system)
      .readJournalFor[journalType.ReadJournalType](journalType.id)
      .eventsByPersistenceId(legacyPersistenceId, nr, Long.MaxValue)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))(Keep.left)
      .run()
    maybeKillSwitch = Option(killSwitch)
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, sequenceNumber: Long) =>
      state = state.copy(seqNr = sequenceNumber)

    case RecoveryCompleted =>
      log.info(s"Recovered migration at ${state.seqNr} sequence number")

    case recoveredLastProcessed: Long =>
      state = state.copy(seqNr = recoveredLastProcessed)

    case unexpected => log.error(s"Received unexpected recovery message: $unexpected")
  }

  override def receiveCommand: Receive = {
    case StartMigration =>
      startUpdatesStream(state.seqNr + 1)
    case StopMigration =>
      maybeKillSwitch.map(_.shutdown())
    case GetMigrationStatus =>
      sender() ! state
    case StreamInitialized =>
      sender() ! Ack
    case StreamCompleted =>
      log.info(s"Received stream completed message.")
      sender() ! Ack
    case _: SaveSnapshotSuccess =>
      log.info(s"Snapshot saved successfully")
    case SaveSnapshotFailure(_, t) =>
      log.error(t, s"Snapshot saving failed")
    case EventEnvelope(_, _, sequenceNr, CrunchDiffMessage(Some(createdAt), _, removals, updates, _, _, _, _)) =>
      log.info(s"received a message to migrate $createdAt")
      val replyTo = sender()
      flightMigrationRouterActor.ask(FlightMessageMigration(sequenceNr, createdAt, removals, updates))
        .onComplete { _ =>
          log.info(s"Got ack from terminal day actor. Persisting latest sequence number processed ($sequenceNr)")
          self ! Processed(sequenceNr, createdAt, replyTo)
        }
    case Processed(seqNr, createdAt, replyTo) =>
      persist(seqNr) { processedSeqNr =>
        context.system.eventStream.publish(processedSeqNr)
        if (processedSeqNr % 100 == 0) saveSnapshot(processedSeqNr)
        log.info(s"Processed $legacyPersistenceId seq no $processedSeqNr - acking back to stream")
        state = MigrationStatus(processedSeqNr, createdAt)
        replyTo ! Ack
      }
    case unexpected =>
      log.info(s"Got this unexpected message $unexpected")
  }
}
