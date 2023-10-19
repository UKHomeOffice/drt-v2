package actors.persistent

import actors.persistent.RedListUpdatesActor.{AddSubscriber, ReceivedSubscriberAck, SendToSubscriber}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import actors.serializers.RedListUpdatesMessageConversion
import akka.persistence._
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.util.Timeout
import drt.shared.redlist._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike}
import uk.gov.homeoffice.drt.protobuf.messages.RedListUpdates.{RedListUpdatesMessage, RemoveUpdateMessage, SetRedListUpdateMessage}
import uk.gov.homeoffice.drt.redlist.{DeleteRedListUpdates, RedListUpdateCommand, RedListUpdates, SetRedListUpdate}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object RedListUpdatesActor {
  case class AddSubscriber(subscriber: SourceQueueWithComplete[List[RedListUpdateCommand]])

  case object SendToSubscriber

  case object ReceivedSubscriberAck
}

class RedListUpdatesActor(val now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[RedListUpdates] {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "red-list-updates"

  override val maybeSnapshotInterval: Option[Int] = None

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case updates: SetRedListUpdateMessage =>
      RedListUpdatesMessageConversion
        .setUpdatesFromMessage(updates)
        .foreach(update => state = state.update(update))

    case delete: RemoveUpdateMessage =>
      delete.date.foreach(effectiveFrom => state = state.remove(effectiveFrom))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case smm: RedListUpdatesMessage =>
      state = RedListUpdatesMessageConversion.updatesFromMessage(smm)
  }

  override def stateToMessage: GeneratedMessage =
    RedListUpdatesMessage(state.updates.values.map(RedListUpdatesMessageConversion.updateToMessage).toList)

  var state: RedListUpdates = initialState

  var maybeSubscriber: Option[SourceQueueWithComplete[List[RedListUpdateCommand]]] = None
  var subscriberMessageQueue: List[RedListUpdateCommand] = List()
  var awaitingSubscriberAck = false

  var readyToEmit: Boolean = false

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val timeout: Timeout = new Timeout(60.seconds)

  override def initialState: RedListUpdates = RedListUpdates(RedList.redListChanges)

  override def receiveCommand: Receive = {
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

    case updates: SetRedListUpdate =>
      log.info(s"Saving RedListUpdates $updates")
      state = state.update(updates)
      persistAndMaybeSnapshot(RedListUpdatesMessageConversion.setUpdatesToMessage(updates))
      subscriberMessageQueue = updates :: subscriberMessageQueue
      self ! SendToSubscriber
      sender() ! updates

    case GetState =>
      log.debug(s"Received GetState request. Sending RedListUpdates with ${state.updates.size} update sets")
      sender() ! state

    case delete: DeleteRedListUpdates =>
      state = state.remove(delete.millis)
      persistAndMaybeSnapshot(RemoveUpdateMessage(Option(delete.millis)))
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
