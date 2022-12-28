package actors.persistent

import actors.AddUpdatesSubscriber
import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.EgateBanksUpdatesActor.{AddSubscriber, ReceivedSubscriberAck, SendToSubscriber}
import actors.persistent.staffing.GetState
import actors.serializers.EgateBanksUpdatesMessageConversion
import akka.actor.ActorRef
import akka.pattern.ask
import akka.persistence._
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import uk.gov.homeoffice.drt.egates._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.EgateBanksUpdates.{PortEgateBanksUpdatesMessage, RemoveEgateBanksUpdateMessage, SetEgateBanksUpdateMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}


object EgateBanksUpdatesActor {
  case class AddSubscriber(subscriber: SourceQueueWithComplete[List[EgateBanksUpdateCommand]])

  case object SendToSubscriber

  case object ReceivedSubscriberAck

  def terminalEgatesProvider(egateBanksUpdatesActor: ActorRef)
                            (implicit timeout: Timeout, ec: ExecutionContext): Terminal => Future[EgateBanksUpdates] = (terminal: Terminal) => egateBanksUpdatesActor
    .ask(GetState)
    .mapTo[PortEgateBanksUpdates]
    .map(_.updatesByTerminal.getOrElse(terminal, throw new Exception(s"No egates found for terminal $terminal")))
}

class EgateBanksUpdatesActor(val now: () => SDateLike,
                             defaults: Map[Terminal, EgateBanksUpdates],
                             offsetMinutes: Int,
                             durationMinutes: Int,
                             maxForecastDays: Int) extends RecoveryActorLike with PersistentDrtActor[PortEgateBanksUpdates] {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "egate-banks-updates"

  override val maybeSnapshotInterval: Option[Int] = None

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case updates: SetEgateBanksUpdateMessage =>
      EgateBanksUpdatesMessageConversion
        .setEgateBanksUpdateFromMessage(updates)
        .foreach(update => state = state.update(update))

    case delete: RemoveEgateBanksUpdateMessage =>
      EgateBanksUpdatesMessageConversion
        .removeEgateBanksUpdateFromMessage(delete)
        .foreach(d => state = state.remove(d))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case smm: PortEgateBanksUpdatesMessage =>
      state = EgateBanksUpdatesMessageConversion.portUpdatesFromMessage(smm)
  }

  override def stateToMessage: GeneratedMessage =
    EgateBanksUpdatesMessageConversion.portUpdatesToMessage(state)

  var state: PortEgateBanksUpdates = initialState

  var maybeSubscriber: Option[SourceQueueWithComplete[List[EgateBanksUpdateCommand]]] = None
  var subscriberMessageQueue: List[EgateBanksUpdateCommand] = List()
  var awaitingSubscriberAck = false

  var maybeCrunchRequestQueueActor: Option[ActorRef] = None
  var readyToEmit: Boolean = false

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val timeout: Timeout = new Timeout(60.seconds)

  override def initialState: PortEgateBanksUpdates = PortEgateBanksUpdates(defaults)

  override def receiveCommand: Receive = {
    case AddUpdatesSubscriber(crunchRequestQueue) =>
      log.info("Received crunch request actor")
      maybeCrunchRequestQueueActor = Option(crunchRequestQueue)

    case AddSubscriber(subscriber) =>
      log.info(s"Received subscriber")
      maybeSubscriber = Option(subscriber)

    case SendToSubscriber =>
      maybeSubscriber.foreach { sub =>
        log.info("Check if we have something to send")
        if (!awaitingSubscriberAck) {
          if (subscriberMessageQueue.nonEmpty) {
            log.info("Sending egate updates to subscriber")
            sub.offer(subscriberMessageQueue).onComplete {
              case Success(result) =>
                if (result != Enqueued) log.error(s"Failed to enqueue red list updates")
                self ! ReceivedSubscriberAck
              case Failure(t) =>
                log.error(s"Failed to enqueue red list updates", t)
                self ! ReceivedSubscriberAck
            }
            subscriberMessageQueue = List()
            awaitingSubscriberAck = true
          } else log.info("Nothing to send")
        } else log.info("Still awaiting subscriber Ack")
      }

    case ReceivedSubscriberAck =>
      log.info("Received subscriber ack")
      awaitingSubscriberAck = false
      if (subscriberMessageQueue.nonEmpty) self ! SendToSubscriber

    case updates: SetEgateBanksUpdate =>
      log.info(s"Saving EgateBanksUpdates $updates")

      maybeCrunchRequestQueueActor.foreach { requestActor =>
        (updates.firstMinuteAffected to SDate.now().addDays(maxForecastDays).millisSinceEpoch by MilliTimes.oneHourMillis).map { millis =>
          requestActor ! CrunchRequest(SDate(millis).toLocalDate, offsetMinutes, durationMinutes)
        }
      }

      state = state.update(updates)
      persistAndMaybeSnapshot(EgateBanksUpdatesMessageConversion.setEgateBanksUpdatesToMessage(updates))
      subscriberMessageQueue = updates :: subscriberMessageQueue
      self ! SendToSubscriber
      sender() ! updates

    case GetState =>
      log.debug(s"Received GetState request. Sending PortEgateBanksUpdates with ${state.size} update sets")
      sender() ! state

    case delete: DeleteEgateBanksUpdates =>
      state = state.remove(delete)
      persistAndMaybeSnapshot(EgateBanksUpdatesMessageConversion.removeEgateBanksUpdateToMessage(delete))
      subscriberMessageQueue = delete :: subscriberMessageQueue
      self ! SendToSubscriber
      sender() ! delete

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message ${unexpected.getClass}")
  }
}
