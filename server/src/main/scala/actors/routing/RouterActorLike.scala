package actors.routing

import actors.SetCrunchRequestQueue
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.routing.minutes.MinutesActorLike.ProcessNextUpdateRequest
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.DataUpdates.Updates

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

trait RouterActorLikeWithSubscriber[U <: Updates, P] extends RouterActorLike[U, P] {
  var maybeUpdatesSubscriber: Option[ActorRef] = None

  override def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[UpdatedMillis] =
    super.handleUpdatesAndAck(updates, replyTo).map { updatedMillis =>
      if (shouldSendEffectsToSubscriber(updates))
        maybeUpdatesSubscriber.foreach(_ ! updatedMillis)
      updatedMillis
    }

  override def receiveUtil: Receive = super.receiveUtil orElse {
    case SetCrunchRequestQueue(queueActor) =>
      log.info("Received subscriber")
      maybeUpdatesSubscriber = Option(queueActor)
  }
}

trait RouterActorLike[U <: Updates, P] extends Actor with ActorLogging {
  var processingRequest: Boolean = false

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, U)] = List()

  def partitionUpdates: PartialFunction[U, Map[P, U]]

  def updatePartition(partition: P, updates: U): Future[UpdatedMillis]

  def receiveQueries: Receive

  def shouldSendEffectsToSubscriber: U => Boolean

  def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[UpdatedMillis] = {
    processingRequest = true
    val eventualEffects = updateAll(updates)
    eventualEffects
      .onComplete { _ =>
        processingRequest = false
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
    eventualEffects
  }

  def updateAll(updates: U): Future[UpdatedMillis] = {
    val eventualUpdatedMinutesDiff: Source[UpdatedMillis, NotUsed] =
      Source(partitionUpdates(updates)).mapAsync(1) {
        case (partition, updates) => updatePartition(partition, updates)
      }
    combineUpdateEffectsStream(eventualUpdatedMinutesDiff)
  }

  private def combineUpdateEffectsStream(effects: Source[UpdatedMillis, NotUsed]): Future[UpdatedMillis] =
    effects
      .fold[UpdatedMillis](UpdatedMillis.empty)(_ ++ _)
      .log(getClass.getName)
      .runWith(Sink.seq)
      .map(_.foldLeft[UpdatedMillis](UpdatedMillis.empty)(_ ++ _))
      .recover { case t =>
        log.error(t, "Failed to combine update effects")
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

    case StreamCompleted =>
      log.info(s"Stream completed. Shutting down")
      context.stop(self)

    case StreamFailure(t) => log.error(s"Stream failed", t)
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
