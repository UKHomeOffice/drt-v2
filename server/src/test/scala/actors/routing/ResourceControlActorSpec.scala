package actors.routing

import actors.routing.ResourceControlActor.{ProcessNextResourceRequest, RequestFinished}
import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe
import services.crunch.CrunchTestLike

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

class ResourceControlActorSpec extends CrunchTestLike {
  "A control actor should send a request" >> {
    val probe = TestProbe()
    val callResource: (String, String) => Future[String] = (resource: String, request: String) => {
      probe.ref ! (resource, request)
      Future.successful(resource + request)
    }
    val actor = system.actorOf(Props(new ResourceControlActor[String, String, String](callResource)))

    actor ! ("A", "A1")

    probe.expectMsg(("A", "A1"))

    success
  }

  "A control actor should send a request to the same resource sequentially" >> {
    val probe = TestProbe()
    val callResource: (String, String) => Future[String] = (resource: String, request: String) => {
      probe.ref ! (resource, request)
      Future({
        Thread.sleep(100)
        probe.ref ! s"$request done"
        resource + request
      })
    }
    val actor = system.actorOf(Props(new ResourceControlActor[String, String, String](callResource)))

    actor ! ("A", "A1")
    actor ! ("A", "A2")

    probe.expectMsg(("A", "A1"))
    probe.expectMsg("A1 done")
    probe.expectMsg(("A", "A2"))
    probe.expectMsg("A2 done")

    success
  }
}
