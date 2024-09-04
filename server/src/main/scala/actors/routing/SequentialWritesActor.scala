package actors.routing

import actors.routing.minutes.MinutesActorLike.{FinishedProcessingRequest, ProcessNextUpdateRequest, QueueUpdateRequest}
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.StatusReply
import akka.stream.Materializer
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SequentialWritesActor[U](performUpdate: U => Future[Any]) extends Actor with ActorLogging {
  implicit val dispatcher: ExecutionContext = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(90.seconds)

  var requestsQueue: List[(ActorRef, U)] = List.empty
  var processingRequest: Boolean = false

  def handleUpdateAndAck(update: U, replyTo: ActorRef): Any = {
    processingRequest = true
    val eventualAck = performUpdate(update)
    eventualAck
      .onComplete { tryResponse =>
        tryResponse match {
          case Success(_) =>
            replyTo ! StatusReply.Ack
          case Failure(exception) =>
            log.error(exception, s"Failed to handle update $update. Re-queuing ${update.getClass.getSimpleName}")
            replyTo ! StatusReply.Error(exception)
            self ! QueueUpdateRequest(update, replyTo)
        }
        processingRequest = false
        self ! ProcessNextUpdateRequest
      }

    eventualAck
  }

  override def receive: Receive = receiveProcessRequest orElse receiveUpdates

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

    case FinishedProcessingRequest =>
      processingRequest = false
      self ! ProcessNextUpdateRequest
  }

  private def receiveUpdates: Receive = {
    case qur: QueueUpdateRequest[U] =>
      requestsQueue = (qur.replyTo, qur.update) :: requestsQueue
      self ! ProcessNextUpdateRequest

    case update: U =>
      requestsQueue = (sender(), update) :: requestsQueue
      self ! ProcessNextUpdateRequest
  }
}
