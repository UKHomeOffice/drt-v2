package actors.routing

import actors.routing.ResourceControlActor.{ProcessNextResourceRequest, RequestFinished}
import akka.actor.{Actor, ActorRef}

import scala.concurrent.{ExecutionContextExecutor, Future}

object ResourceControlActor {
  case class ProcessNextResourceRequest[A](resource: A)

  case class RequestFinished[A](resource: A)
}

class ResourceControlActor[RES, REQ, RESP](useResource: (RES, REQ) => Future[REQ]) extends Actor {
  var inUse: List[RES] = List()
  var requestQueue: Map[RES, List[(ActorRef, REQ)]] = Map()

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  override def receive: Receive = {
    case (resource: RES, request: REQ) =>
      queueRequest(sender(), resource, request)
      self ! ProcessNextResourceRequest(resource)

    case ProcessNextResourceRequest(resource: RES) =>
      if (!inUse.contains(resource)) {
        requestQueue.get(resource).foreach {
          case (replyTo, next) :: remainingRequests =>
            inUse = resource :: inUse
            requestQueue = requestQueue.updated(resource, remainingRequests)
            useResource(resource, next).map { response =>
              replyTo ! response
              self ! RequestFinished(resource)
            }
          case Nil =>
        }
      }

    case RequestFinished(resource: RES) =>
      inUse = inUse.filterNot(_ == resource)
      self ! ProcessNextResourceRequest(resource)
  }

  def queueRequest(sender: ActorRef, resource: RES, request: REQ): Unit =
    requestQueue = requestQueue.get(resource) match {
      case Some(list) =>
        requestQueue.updated(resource, list :+ (sender, request))
      case None =>
        requestQueue.updated(resource, List((sender, request)))
    }
}
