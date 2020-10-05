package actors.migration

import actors.StreamingJournalLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import akka.actor.{ActorLogging, ActorRef}
import akka.pattern._
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, FlightWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

case object StartMigration

case object StopMigration

case class FlightMessageMigration(
                                   sequenceId: Long,
                                   createdAt: MillisSinceEpoch,
                                   flightRemovalsMessage: Seq[UniqueArrivalMessage],
                                   flightsUpdateMessages: Seq[FlightWithSplitsMessage],
                                 )

class FlightsMigrationActor(journalType: StreamingJournalLike, flightMigrationRouterActor: ActorRef)
  extends PersistentActor with ActorLogging {

  override val persistenceId = "crunch-state-migration"

  var maybeKillSwitch: Option[UniqueKillSwitch] = None
  var lastProcessedSequenceNumber: Long = 0

  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = Timeout(1 second)
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val startUpdatesStream: Long => Unit = (nr: Long) => if (maybeKillSwitch.isEmpty) {
    val (_, killSwitch) = PersistenceQuery(context.system)
      .readJournalFor[journalType.ReadJournalType](journalType.id)
      .eventsByPersistenceId(persistenceId, nr, Long.MaxValue)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))(Keep.left)
      .run()
    maybeKillSwitch = Option(killSwitch)
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(md, ss: Int) =>
      lastProcessedSequenceNumber = ss

    case RecoveryCompleted =>
      log.info(s"Recovered migration at $lastProcessedSequenceNumber sequence number")

    case recoveredLastProcessed: Int =>
      lastProcessedSequenceNumber = recoveredLastProcessed

  }

  override def receiveCommand: Receive = {
    case StartMigration =>
      startUpdatesStream(lastProcessedSequenceNumber + 1)
    case StopMigration =>
      maybeKillSwitch.map(_.shutdown())
    case EventEnvelope(_, _, sequenceNr, CrunchDiffMessage(Some(createdAt), _, removals, updates, _, _, _, _)) =>
      flightMigrationRouterActor.ask(FlightMessageMigration(sequenceNr, createdAt, removals, updates))
        .onComplete(_ => {
          persist(sequenceNr) { currentSequenceNumber =>

            context.system.eventStream.publish(currentSequenceNumber)

            if (currentSequenceNumber % 100 == 0) saveSnapshot(currentSequenceNumber)

            lastProcessedSequenceNumber = currentSequenceNumber
          }
          sender() ! Ack
        })
  }
}
