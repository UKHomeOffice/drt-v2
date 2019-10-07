package controllers.model

import akka.pattern.AskableActorRef
import drt.shared.CrunchApi.PortStateError
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ActorDataRequest {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def portState[X](actorRef: AskableActorRef, message: Any)(implicit ec: ExecutionContext): Future[Either[PortStateError, Option[X]]] = {
    actorRef
      .ask(message)(30 seconds)
      .map {
        case Some(ps: X) => Right(Option(ps))
        case _ => Right(None)
      }
      .recover {
        case t => Left(PortStateError(t.getMessage))
      }
  }
}
