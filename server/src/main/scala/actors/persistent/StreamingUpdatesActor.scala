package actors.persistent

import actors.StreamingJournalLike
import actors.daily.StreamingUpdatesLike.StopUpdates
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.StatusReply.Ack
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.StreamSupervision
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamInitialized}
import uk.gov.homeoffice.drt.actor.commands.Commands.AddUpdatesSubscriber

object StreamingUpdatesActor {
  def startUpdatesStream(system: ActorSystem, journalType: StreamingJournalLike, persistenceId: String, receivingRef: ActorRef)
                        (implicit mat: Materializer): Long => UniqueKillSwitch =
    (sequenceNumber: Long) => {
      val (_, killSwitch) = PersistenceQuery(system)
        .readJournalFor[journalType.ReadJournalType](journalType.id)
        .eventsByPersistenceId(persistenceId, sequenceNumber, Long.MaxValue)
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(Sink.actorRefWithBackpressure(receivingRef, StreamInitialized, Ack, StreamCompleted, _ => {}))(Keep.left)
        .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
        .run()
      killSwitch
    }
}

class StreamingUpdatesActor[T, S](val persistenceId: String,
                                  journalType: StreamingJournalLike,
                                  initialState: T,
                                  snapshotMessageToState: Any => T,
                                  eventToState: (T, Any) => (T, S),
                                  query: (() => T, () => ActorRef) => PartialFunction[Any, Unit],
                                 ) extends PersistentActor {

  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val mat: Materializer = Materializer.createMaterializer(context)

  var maybeKillSwitch: Option[UniqueKillSwitch] = None

  var state: T = initialState

  var subscriber = Option.empty[ActorRef]

  val startUpdatesStream: Long => UniqueKillSwitch =
    StreamingUpdatesActor.startUpdatesStream(context.system, journalType, persistenceId, self)

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, msg) =>
      state = snapshotMessageToState(msg)
    case event: GeneratedMessage =>
      state = eventToState(state, event)._1
    case RecoveryCompleted =>
      log.info(s"Recovery complete. Starting update stream")
      val killSwitch = startUpdatesStream(lastSequenceNr)
      maybeKillSwitch = Option(killSwitch)
  }

  private val receiveEvent: Receive = {
    case EventEnvelope(_, _, _, msg) =>
      val (newState, subscriberEvent) = eventToState(state, msg)
      state = newState
      subscriber.foreach { s =>
        s ! subscriberEvent
      }
      sender() ! Ack
  }

  private val receiveStreamEvent: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted => log.warn("Received shutdown")

    case StopUpdates =>
      maybeKillSwitch.foreach(_.shutdown())
  }

  private val receiveSubscriber: Receive = {
    case AddUpdatesSubscriber(newSub) =>
      subscriber = Option(newSub)
  }

  val receiveQuery: Receive = query(() => state, sender)

  private val receiveUnknown: Receive = {
    case unexpected => log.info(s"Received unexpected message ${unexpected.getClass}")
  }

  override def receiveCommand: Receive =
    receiveEvent orElse
      receiveStreamEvent orElse
      receiveSubscriber orElse
      receiveQuery orElse
      receiveUnknown
}
