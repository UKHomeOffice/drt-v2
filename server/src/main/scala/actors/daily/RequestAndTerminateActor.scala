package actors.daily

import akka.actor.{Actor, ActorRef, PoisonPill, Terminated}
import akka.pattern.{StatusReply, ask}
import akka.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.ExecutionContextExecutor

case class Terminate(actor: ActorRef)

case class RequestAndTerminate(actor: ActorRef, request: Any)

class RequestAndTerminateActor(implicit defaultTimeout: Timeout) extends Actor {
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  val log: Logger = LoggerFactory.getLogger(getClass)
  private var deathWatchReplyToAndResponse: Map[ActorRef, (ActorRef, Any)] = Map[ActorRef, (ActorRef, Any)]()

  override def receive: Receive = {
    case Terminate(actor) =>
      val replyTo = sender()
      self ! ActorReplyToResponse(actor, replyTo, StatusReply.Ack)

    case RequestAndTerminate(actor, request) =>
      executeRequest(actor, request)

    case ActorReplyToResponse(actor, replyTo, response) =>
      deathWatchReplyToAndResponse = deathWatchReplyToAndResponse + (actor -> ((replyTo, response)))
      context.watch(actor)
      actor ! PoisonPill

    case Terminated(terminatedActor) =>
      deathWatchReplyToAndResponse.get(terminatedActor) match {
        case None => log.error("Failed to find a matching terminated actor to respond to")
        case Some((replyTo, response)) =>
          deathWatchReplyToAndResponse = deathWatchReplyToAndResponse - terminatedActor
          replyTo ! response
      }
  }

  private def executeRequest(actor: ActorRef, request: Any): Unit = {
    val replyTo = sender()
    val eventualResponse = actor.ask(request)
    eventualResponse
      .foreach { response =>
        self ! ActorReplyToResponse(actor, replyTo, response)
      }
    eventualResponse
      .recover {
        case e: Throwable =>
          log.error(s"Failed to get a response from actor $actor", e)
          self ! ActorReplyToResponse(actor, replyTo, StatusReply.Error(e))
      }
  }
}

case class ActorReplyToResponse(actor: ActorRef, replyTo: ActorRef, response: Any)
