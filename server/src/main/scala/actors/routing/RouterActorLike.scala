package actors.routing

import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.routing.minutes.MinutesActorLike.ProcessNextUpdateRequest
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{StatusReply, ask, pipe}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import uk.gov.homeoffice.drt.DataUpdates.Updates
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}
import uk.gov.homeoffice.drt.actor.commands.Commands.AddUpdatesSubscriber

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

trait RouterActorLikeWithSubscriber[U <: Updates, P] extends RouterActorLike[U, P] {
  var updatesSubscribers: List[ActorRef] = List.empty

  override def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[UpdatedMillis] =
    super.handleUpdatesAndAck(updates, replyTo).map { updatedMillis =>
      if (shouldSendEffectsToSubscriber(updates))
        updatesSubscribers.foreach(_ ! updatedMillis)
      updatedMillis
    }

  override def receiveUtil: Receive = super.receiveUtil orElse {
    case AddUpdatesSubscriber(queueActor) =>
      log.info("Received subscriber")
      updatesSubscribers = queueActor :: updatesSubscribers
  }
}

trait RouterActorLikeWithSubscriber2[U <: Updates, P] extends RouterActorLike2[U, P] {
  override def receiveUtil: Receive = super.receiveUtil orElse {
    case AddUpdatesSubscriber(queueActor) =>
      log.info("Received subscriber - forwarding to sequential access actor")
      sequentialUpdatesActor ! AddUpdatesSubscriber(queueActor)
  }
}

trait RouterActorLike[U <: Updates, P] extends Actor with ActorLogging {
  var processingRequest: Boolean = false

  implicit val dispatcher: ExecutionContext = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(90.seconds)

  var updateRequestsQueue: List[(ActorRef, U)] = List.empty

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
        replyTo ! StatusReply.Ack
        self ! ProcessNextUpdateRequest
      }
    eventualEffects
  }

  private def updateAll(updates: U): Future[UpdatedMillis] = {
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
    case StreamInitialized => sender() ! StatusReply.Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed: ${t.getMessage}")
  }

  private def receiveUpdates: Receive = {
    case updates: U =>
      updateRequestsQueue = (sender(), updates) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest
  }

  private def receiveProcessRequest: Receive = {
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

  private def receiveUnexpected: Receive = {
    case unexpected => log.warning(s"Got an unexpected message: ${unexpected.getClass}")
  }
}

trait RouterActorLike2[U <: Updates, P] extends Actor with ActorLogging {
  var processingRequest: Boolean = false

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val sequentialUpdatesActor: ActorRef

  def partitionUpdates: PartialFunction[U, Map[P, U]]

  def updatePartition(partition: P, updates: U): Future[UpdatedMillis]

  def receiveQueries: Receive

  def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[Any] =
    sequentialUpdatesActor.ask(updates).pipeTo(replyTo)

  override def receive: Receive =
    receiveUtil orElse
      receiveUpdates orElse
      receiveQueries orElse
      receiveUnexpected

  def receiveUtil: Receive = {

    case StreamInitialized => sender() ! StatusReply.Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed: ${t.getMessage}")
  }

  private def receiveUpdates: Receive = {
    case updates: U =>
      handleUpdatesAndAck(updates, sender())
  }

  private def receiveUnexpected: Receive = {
    case unexpected =>
      log.warning(s"Got an unexpected message: ${unexpected.getClass}")
  }
}

class SequentialWritesActor[U](performUpdate: U => Future[Any]) extends Actor with ActorLogging {
  implicit val dispatcher: ExecutionContext = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(90.seconds)

  var requestsQueue: List[(ActorRef, U)] = List.empty
  var processingRequest: Boolean = false
  var subscribers: List[ActorRef] = List.empty

  def handleUpdateAndAck(update: U, replyTo: ActorRef): Any = {
    processingRequest = true
    val eventualAck = performUpdate(update)
    eventualAck
      .onComplete { response =>
        processingRequest = false
        subscribers.foreach(_ ! response)
        replyTo ! StatusReply.Ack
        self ! ProcessNextUpdateRequest
      }
    eventualAck
  }

  override def receive: Receive =
    receiveSubscriber orElse
      receiveUpdates orElse
      receiveProcessRequest orElse
      receiveUnexpected

  private def receiveSubscriber: Receive = {
    case AddUpdatesSubscriber(subscriber) =>
      subscribers = subscriber :: subscribers
  }

  private def receiveUpdates: Receive = {
    case updates: U =>
      requestsQueue = (sender(), updates) :: requestsQueue
      self ! ProcessNextUpdateRequest
  }

  private def receiveProcessRequest: Receive = {
    case ProcessNextUpdateRequest =>
      if (!processingRequest) {
        requestsQueue match {
          case (replyTo, nextUpdate) :: tail =>
            handleUpdateAndAck(nextUpdate, replyTo)
            requestsQueue = tail
          case Nil =>
            log.debug("Update requests queue is empty. Nothing to do")
        }
      }
  }

  private def receiveUnexpected: Receive = {
    case unexpected => log.warning(s"Got an unexpected message: ${unexpected.getClass}")
  }
}

