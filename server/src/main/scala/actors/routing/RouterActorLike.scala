package actors.routing

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.minutes.MinutesActorLike.ProcessNextUpdateRequest
import actors.queues.QueueLikeActor.{NoAffect, UpdateAffect}
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.FlightsApi.Updates

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._

trait RouterActorLike[U <: Updates, P] extends Actor with ActorLogging {
  val updatesSubscriber: ActorRef
  var processingRequest: Boolean = false

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, U)] = List()

  def partitionUpdates: PartialFunction[U, Map[P, U]]

  def affectsFromUpdate(partition: P, updates: U): Future[UpdateAffect]

  def receiveQueries: Receive

  def handleUpdatesAndAck(updates: U, replyTo: ActorRef): Future[UpdateAffect] = {
    processingRequest = true
    val eventualUpdatesDiff = updateByTerminalDayAndGetDiff(updates)
    eventualUpdatesDiff
      .map(updateAffect => updatesSubscriber ! updateAffect)
      .onComplete { _ =>
        processingRequest = false
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
    eventualUpdatesDiff
  }

  def updateByTerminalDayAndGetDiff(updates: U): Future[UpdateAffect] = {
    val eventualUpdatedMinutesDiff: Source[UpdateAffect, NotUsed] =
      Source(partitionUpdates(updates)).mapAsync(1) {
        case (partition, updates) => affectsFromUpdate(partition, updates)
      }
    combineUpdateAffectsStream(eventualUpdatedMinutesDiff)
  }

  private def combineUpdateAffectsStream(affects: Source[UpdateAffect, NotUsed]): Future[UpdateAffect] =
    affects
      .fold[UpdateAffect](NoAffect)(_ ++ _)
      .log(getClass.getName)
      .runWith(Sink.seq)
      .map(_.foldLeft[UpdateAffect](NoAffect)(_ ++ _))
      .recover { case t =>
        log.error(t, "Failed to combine update affects")
        NoAffect
      }

  override def receive: Receive =
    receiveUtil orElse
      receiveUpdates orElse
      receiveQueries orElse
      receiveUnexpected

  def receiveUtil: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)
  }

  def receiveUpdates: Receive = {
    case updates: U =>
      updateRequestsQueue = (sender(), updates) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest

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
