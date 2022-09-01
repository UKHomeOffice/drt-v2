package actors.routing

import actors.routing.SequentialAccessActor.{ProcessNextRequest, RequestFinished}
import akka.actor.{Actor, ActorRef}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.DataUpdates.Combinable
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContextExecutor, Future}


object SequentialAccessActor {
  case object ProcessNextRequest

  case object RequestFinished
}

class SequentialAccessActor[RES, REQ, RESP <: Combinable[RESP]](
                                                                 resourceRequest: (RES, REQ) => Future[RESP],
                                                                 splitByResource: REQ => Iterable[(RES, REQ)],
                                                               ) extends Actor {
  private val log = LoggerFactory.getLogger(getClass)

  var requests: List[(ActorRef, Iterable[(RES, REQ)])] = List()
  var busy: Boolean = false

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)

  override def receive: Receive = {
    case ProcessNextRequest =>
      if (!busy) processNextRequest(requests)

    case RequestFinished =>
      setBusy(false)
      self ! ProcessNextRequest

    case request: REQ =>
      addRequests(sender(), splitByResource(request))
      self ! ProcessNextRequest
  }

  private def processNextRequest(requestsToProcess: List[(ActorRef, Iterable[(RES, REQ)])]): Unit =
    requestsToProcess match {
      case Nil =>
        log.info("No requests left to process")
      case (replyTo, next) :: tail =>
        setBusy(true)
        setRequests(tail)
        Source(next.toList)
          .mapAsync(1) {
            case (resource, request) => resourceRequest(resource, request)
          }
          .runWith(Sink.reduce[RESP](_ ++ _))
          .onComplete { maybeResponse =>
            maybeResponse.foreach(replyTo ! _)
            self ! RequestFinished
          }
    }

  private def setBusy(isBusy: Boolean): Unit = {
    busy = isBusy
  }

  private def addRequests(replyTo: ActorRef, requestsToAdd: Iterable[(RES, REQ)]): Unit = {
    requests = requests :+ ((replyTo, requestsToAdd))
  }

  private def setRequests(requestsToSet: List[(ActorRef, Iterable[(RES, REQ)])]): Unit = {
    requests = requestsToSet
  }
}
