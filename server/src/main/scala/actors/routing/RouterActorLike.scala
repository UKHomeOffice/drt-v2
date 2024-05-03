package actors.routing

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

trait RouterActorLikeWithSubscriber[U <: Updates, P, A] extends RouterActorLike[U, P, A] {
  var updatesSubscribers: List[ActorRef] = List.empty

  override def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[Set[A]] =
    super.handleUpdatesAndAck(updates, replyTo).map { updatedMillis =>
      if (shouldSendEffectsToSubscriber(updates)) {
        updatesSubscribers.foreach { subscriber =>
          if (updatedMillis.nonEmpty) subscriber ! updatedMillis
        }
      }
      updatedMillis
    }

  override def receiveUtil: Receive = super.receiveUtil orElse {
    case AddUpdatesSubscriber(queueActor) =>
      log.info("Received subscriber")
      updatesSubscribers = queueActor :: updatesSubscribers
  }
}

trait RouterActorLikeWithSubscriber2[U <: Updates, P, A] extends RouterActorLike2[U, P, A] {
  override def receiveUtil: Receive = super.receiveUtil orElse {
    case AddUpdatesSubscriber(queueActor) =>
      log.info("Received subscriber - forwarding to sequential access actor")
      sequentialUpdatesActor ! AddUpdatesSubscriber(queueActor)
  }
}

trait RouterActorLike[U <: Updates, P, A] extends Actor with ActorLogging {
  var processingRequest: Boolean = false

  implicit val dispatcher: ExecutionContext = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(90.seconds)

  var updateRequestsQueue: List[(ActorRef, U)] = List.empty

  def partitionUpdates: PartialFunction[U, Map[P, U]]

  def updatePartition(partition: P, updates: U): Future[Set[A]]

  def receiveQueries: Receive

  def shouldSendEffectsToSubscriber: U => Boolean

  def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[Set[A]] = {
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

  private def updateAll(updates: U): Future[Set[A]] = {
    val eventualUpdatedMinutesDiff: Source[Set[A], NotUsed] =
      Source(partitionUpdates(updates)).mapAsync(1) {
        case (partition, updates) => updatePartition(partition, updates)
      }
    combineUpdateEffectsStream(eventualUpdatedMinutesDiff)
  }

  private def combineUpdateEffectsStream(effects: Source[Set[A], NotUsed]): Future[Set[A]] =
    effects
      .fold[Set[A]](Set.empty[A])(_ ++ _)
      .log(getClass.getName)
      .runWith(Sink.seq)
      .map(_.foldLeft[Set[A]](Set.empty[A])(_ ++ _))
      .recover { case t =>
        log.error(t, "Failed to combine update effects")
        Set.empty[A]
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
    case unexpected => log.error(s"Got an unexpected message: ${unexpected.getClass}")
  }
}

trait RouterActorLike2[U <: Updates, P, A] extends Actor with ActorLogging {
  var processingRequest: Boolean = false

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val sequentialUpdatesActor: ActorRef

  def partitionUpdates: PartialFunction[U, Map[P, U]]

  def updatePartition(partition: P, updates: U): Future[Set[A]]

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
      log.error(s"Got an unexpected message: ${unexpected.getClass}")
  }
}



