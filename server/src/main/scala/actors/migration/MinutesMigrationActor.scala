package actors.migration

import actors.{PostgresTables, StreamingJournalLike}
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.migration.FlightsMigrationActor.{MigrationStatus, Processed}
import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.persistence._
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import dispatch.Future
import drt.shared.CrunchApi.MillisSinceEpoch
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchMinuteMessage, FlightWithSplitsMessage, FlightsWithSplitsDiffMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import slick.jdbc.SQLActionBuilder
import slickdb.AkkaPersistenceSnapshotTable

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

case class CrunchMinutesMessageMigration(
                                          sequenceId: Long,
                                          createdAt: MillisSinceEpoch,
                                          minutesMessages: Seq[CrunchMinuteMessage],
                                        )

object MinutesMigrationActor {
  val legacyPersistenceId = "crunch-state"

  val snapshotTable: AkkaPersistenceSnapshotTable = AkkaPersistenceSnapshotTable(PostgresTables)

  def props(journalType: StreamingJournalLike, flightMigrationRouterActor: ActorRef, legacyPersistenceId: String): Props =
    Props(new MinutesMigrationActor(journalType, snapshotTable, flightMigrationRouterActor, legacyPersistenceId))

  case class Processed(seqNr: Long, createdAt: MillisSinceEpoch, replyTo: ActorRef)

  case class MigrationStatus(seqNr: Long, createdAt: MillisSinceEpoch, isRunning: Boolean)

}


class MinutesMigrationActor(journalType: StreamingJournalLike,
                            snapshotTable: AkkaPersistenceSnapshotTable,
                            flightMigrationRouterActor: ActorRef,
                            legacyPersistenceId: String)
  extends PersistentActor with ActorLogging {

  import snapshotTable.tables.profile.api._

  private val config: Config = ConfigFactory.load()
  val maxBufferSize: Int = config.getInt("jdbc-read-journal.max-buffer-size")
  val queryInterval: Int = config.getInt("migration.query-interval-ms")

  override val persistenceId = s"$legacyPersistenceId-migration"

  override def preStart(): Unit = {
    val query: SQLActionBuilder =
      sql"""SELECT MIN(sequence_number)
            FROM journal
            WHERE persistence_id=$legacyPersistenceId
        """
    snapshotTable.db.run(query.as[Int]).onComplete {
      case Success(ints) => ints.headOption.foreach { minSeqNr =>
        log.info(s"First sequence number for $legacyPersistenceId is $minSeqNr")
        state = state.copy(seqNr = minSeqNr - 1)
      }
    }
  }

  var maybeKillSwitch: Option[UniqueKillSwitch] = None
  var state: MigrationStatus = MigrationStatus(0L, 0L, false)

  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = Timeout(1 second)
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val startUpdatesStream: Long => Unit = (nr: Long) => if (maybeKillSwitch.isEmpty && state.isRunning) {
    log.info(s"Starting event stream for $legacyPersistenceId from seq no $nr")
    val (_, killSwitch) = PersistenceQuery(context.system)
      .readJournalFor[journalType.ReadJournalType](journalType.id)
      .currentEventsByPersistenceId(legacyPersistenceId, nr, nr + maxBufferSize - 1)
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
      if (!state.isRunning) {
        state = state.copy(isRunning = true)
        startUpdatesStream(state.seqNr + 1)
      }

    case StopMigration =>
      if (state.isRunning) {
        state = state.copy(isRunning = false)
        maybeKillSwitch.map(_.shutdown())
      }

    case GetMigrationStatus =>
      log.info(s"Sending back state: $state")
      sender() ! state

    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted if state.isRunning =>
      maybeKillSwitch = None
      log.info(s"Received stream completed message. Restarting from ${state.seqNr + 1}")
      after(queryInterval milliseconds, context.system.scheduler)(Future(startUpdatesStream(state.seqNr + 1)))

    case StreamCompleted if !state.isRunning =>
      maybeKillSwitch = None
      log.info(s"Received stream completed message")

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

    case EventEnvelope(_, _, sequenceNr, FlightsWithSplitsDiffMessage(Some(createdAt), removals, updates)) =>
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
        state = state.copy(seqNr = processedSeqNr, createdAt = createdAt)
        replyTo ! Ack
      }

    case unexpected =>
      log.info(s"Got this unexpected message $unexpected")
  }
}
