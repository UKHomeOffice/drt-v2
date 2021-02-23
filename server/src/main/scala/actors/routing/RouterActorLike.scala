package actors.routing

import actors.SetSubscriber
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.minutes.MinutesActorLike.ProcessNextUpdateRequest
import actors.queues.QueueLikeActor.{UpdateEffect, UpdatedMillis}
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.DataUpdates.Updates

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

object RouterActorLike {
}

trait RouterActorLike[U <: Updates, P] extends Actor with ActorLogging {
  var maybeUpdatesSubscriber: Option[ActorRef]
  var processingRequest: Boolean = false

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, U)] = List()

  def partitionUpdates: PartialFunction[U, Map[P, U]]

  def affectsFromUpdate(partition: P, updates: U): Future[UpdateEffect]

  def receiveQueries: Receive

  def shouldSendEffectsToSubscriber: U => Boolean

  def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[UpdateEffect] = {
    processingRequest = true
    val eventualAffects = sendUpdates(updates)
    eventualAffects
      .map { updateEffect =>
        if (shouldSendEffectsToSubscriber(updates)) maybeUpdatesSubscriber.foreach(_ ! updateEffect)
      }
      .onComplete { _ =>
        processingRequest = false
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
    eventualAffects
  }

  def sendUpdatedMillisToSubscriber(eventualUpdatesDiff: Future[UpdateEffect]): Future[Unit] = eventualUpdatesDiff.collect {
    case diffMinutesContainer => maybeUpdatesSubscriber.foreach(_ ! diffMinutesContainer)
  }

  def sendUpdates(updates: U): Future[UpdateEffect] = {
    val eventualUpdatedMinutesDiff: Source[UpdateEffect, NotUsed] =
      Source(partitionUpdates(updates)).mapAsync(1) {
        case (partition, updates) => affectsFromUpdate(partition, updates)
      }
    combineUpdateAffectsStream(eventualUpdatedMinutesDiff)
  }

  private def combineUpdateAffectsStream(affects: Source[UpdateEffect, NotUsed]): Future[UpdateEffect] =
    affects
      .fold[UpdateEffect](UpdatedMillis.empty)(_ ++ _)
      .log(getClass.getName)
      .runWith(Sink.seq)
      .map(_.foldLeft[UpdateEffect](UpdatedMillis.empty)(_ ++ _))
      .recover { case t =>
        log.error(t, "Failed to combine update affects")
        UpdatedMillis.empty
      }

  override def receive: Receive =
    receiveUtil orElse
      receiveUpdates orElse
      receiveProcessRequest orElse
      receiveQueries orElse
      receiveUnexpected

  def receiveUtil: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case setSubscriber: SetSubscriber =>
      log.info(s"Received subscriber actor")
      maybeUpdatesSubscriber = Option(setSubscriber.subscriber)
  }

  def receiveUpdates: Receive = {
    case updates: U =>
      updateRequestsQueue = (sender(), updates) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest
  }

  def receiveProcessRequest: Receive = {
    case ProcessNextUpdateRequest =>
      if (!processingRequest) {
        updateRequestsQueue match {
          case (replyTo, updates) :: tail =>
            handleUpdatesAndAck(updates, replyTo)
            updateRequestsQueue = tail
          case Nil =>
            log.debug("Update requests queue is empty. Nothing to do")
        }
      }
  }

  def receiveUnexpected: Receive = {
    case unexpected => log.warning(s"Got an unexpected message: ${unexpected.getClass}")
  }
}
