package actors.daily

import java.util.UUID

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MinutesContainer
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, Terminals}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContextExecutor


case class RequestAndTerminate(actor: ActorRef, request: Any)

class RequestAndTerminateActor(implicit timeout: Timeout) extends Actor {
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  val log: Logger = LoggerFactory.getLogger(getClass)
  var deathWatchReplyToAndResponse: Map[ActorRef, (ActorRef, Any)] = Map[ActorRef, (ActorRef, Any)]()

  override def receive: Receive = {
    case RequestAndTerminate(actor, request) =>
      val replyTo = sender()
      val eventualDiff = actor.ask(request)
      eventualDiff.foreach { response =>
        self ! ActorReplyToResponse(actor, replyTo, response)
      }

    case ActorReplyToResponse(actor, replyTo, response) =>
      deathWatchReplyToAndResponse = deathWatchReplyToAndResponse + (actor -> (replyTo, response))
      context.watch(actor)
      actor ! PoisonPill

    case Terminated(terminatedActor) =>
      log.info("Actor terminated. Replying to sender")
      deathWatchReplyToAndResponse.get(terminatedActor) match {
        case None => log.error("Failed to find a matching terminated actor to respond to")
        case Some((replyTo, response)) =>
          deathWatchReplyToAndResponse = deathWatchReplyToAndResponse - terminatedActor
          log.info(s"Sending response to sender")
          replyTo ! response
      }
  }
}

case class ActorReplyToResponse(actor: ActorRef, replyTo: ActorRef, response: Any)
