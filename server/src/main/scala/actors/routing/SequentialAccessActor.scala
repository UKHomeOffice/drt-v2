package actors.routing

import actors.AddUpdatesSubscriber
import actors.acking.AckingReceiver.Ack
import actors.routing.SequentialAccessActor.{ProcessNextRequest, RequestFinished}
import akka.actor.{Actor, ActorRef}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.DataUpdates.Combinable

import scala.concurrent.{ExecutionContextExecutor, Future}


object SequentialAccessActor {
  case object ProcessNextRequest

  case object RequestFinished
}

class SequentialAccessActor[RES, REQ, UPDATES <: Combinable[UPDATES]](resourceRequest: (RES, REQ) => Future[UPDATES],
                                                                      splitByResource: REQ => Iterable[(RES, REQ)],
                                                                     ) extends Actor {
  private val log = LoggerFactory.getLogger(getClass)

  var updatesSubscribers: List[ActorRef] = List.empty
  var requests: List[(ActorRef, Iterable[(RES, REQ)])] = List.empty

  private var busy: Boolean = false
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)

  def shouldSendEffectsToSubscribers(request: REQ): Boolean = true

  override def receive: Receive = {
    case ProcessNextRequest =>
      if (!busy) processNextRequest(requests)

    case RequestFinished =>
      setBusy(false)
      self ! ProcessNextRequest

    case AddUpdatesSubscriber(queueActor) =>
      log.info("Received subscriber")
      updatesSubscribers = queueActor :: updatesSubscribers

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
          .runWith(Sink.reduce[UPDATES](_ ++ _))
          .onComplete { maybeUpdates =>
            next.headOption.foreach {
              case (_, request) =>
                if (next.nonEmpty && shouldSendEffectsToSubscribers(request)) {
                  for {
                    updates <- maybeUpdates.toOption.toList
                    subscriber <- updatesSubscribers
                  } yield subscriber ! updates
                }
            }

            replyTo ! Ack
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
