package actors.persistent

import actors.SetCrunchRequestQueue
import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.EgateBanksUpdatesActor.{AddSubscriber, ReceivedSubscriberAck, SendToSubscriber}
import actors.persistent.Sizes.oneMegaByte
import actors.persistent.staffing.GetState
import actors.serializers.EgateBanksUpdatesMessageConversion
import akka.actor.ActorRef
import akka.persistence._
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.EgateBanksUpdates.{EgateBanksUpdatesMessage, RemoveEgateBanksUpdateMessage, SetEgateBanksUpdateMessage}
import uk.gov.homeoffice.drt.egates.{DeleteEgateBanksUpdates, EgateBanksUpdateCommand, EgateBanksUpdates, SetEgateBanksUpdate}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}


object EgateBanksUpdatesActor {
  case class AddSubscriber(subscriber: SourceQueueWithComplete[List[EgateBanksUpdateCommand]])

  case object SendToSubscriber

  case object ReceivedSubscriberAck
}

class EgateBanksUpdatesActor(val now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[EgateBanksUpdates] {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "egate-banks-updates"

  override val snapshotBytesThreshold: Int = oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = None

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case updates: SetEgateBanksUpdateMessage =>
      EgateBanksUpdatesMessageConversion
        .setUpdatesFromMessage(updates)
        .foreach(update => state = state.update(update))

    case delete: RemoveEgateBanksUpdateMessage =>
      delete.date.map(effectiveFrom => state = state.remove(effectiveFrom))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case smm: EgateBanksUpdatesMessage =>
      state = EgateBanksUpdatesMessageConversion.updatesFromMessage(smm)
  }

  override def stateToMessage: GeneratedMessage =
    EgateBanksUpdatesMessage(state.updates.map(EgateBanksUpdatesMessageConversion.banksUpdateToMessage))

  var state: EgateBanksUpdates = initialState

  var maybeSubscriber: Option[SourceQueueWithComplete[List[EgateBanksUpdateCommand]]] = None
  var subscriberMessageQueue: List[EgateBanksUpdateCommand] = List()
  var awaitingSubscriberAck = false

  var maybeCrunchRequestQueueSource: Option[ActorRef] = None
  var readyToEmit: Boolean = false

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val timeout: Timeout = new Timeout(60.seconds)

  override def initialState: EgateBanksUpdates = EgateBanksUpdates(List())

  override def receiveCommand: Receive = {
    case SetCrunchRequestQueue(crunchRequestQueue) =>

    case AddSubscriber(subscriber) =>
      log.info(s"Received subscriber")
      maybeSubscriber = Option(subscriber)

    case SendToSubscriber =>
      maybeSubscriber.foreach { sub =>
        log.info("Check if we have something to send")
        if (!awaitingSubscriberAck) {
          if (subscriberMessageQueue.nonEmpty) {
            log.info("Sending red list updates to subscriber")
            sub.offer(subscriberMessageQueue).onComplete{
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
      state = state.update(updates)
      persistAndMaybeSnapshot(EgateBanksUpdatesMessageConversion.setUpdatesToMessage(updates))
      subscriberMessageQueue = updates :: subscriberMessageQueue
      self ! SendToSubscriber
      sender() ! updates

    case GetState =>
      log.debug(s"Received GetState request. Sending EgateBanksUpdates with ${state.updates.size} update sets")
      sender() ! state

    case delete: DeleteEgateBanksUpdates =>
      state = state.remove(delete.millis)
      persistAndMaybeSnapshot(RemoveEgateBanksUpdateMessage(Option(delete.millis)))
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
