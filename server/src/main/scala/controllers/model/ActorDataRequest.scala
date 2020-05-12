package controllers.model

import actors.{GetPortState, GetUpdatesSince}
import akka.actor.ActorRef
import akka.pattern.ask
import drt.shared.CrunchApi.{PortStateError, PortStateUpdates}
import drt.shared.PortState
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object ActorDataRequest {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def portState(actorRef: ActorRef, message: GetPortState)(implicit ec: ExecutionContext): Future[PortState] =
    actorRef.ask(message)(30 seconds).mapTo[PortState]

  def portStateUpdates(actorRef: ActorRef, message: GetUpdatesSince)(implicit ec: ExecutionContext): Future[Either[PortStateError, Option[PortStateUpdates]]] = {
    actorRef
      .ask(message)(30 seconds).mapTo[Option[PortStateUpdates]]
      .map {
        case Some(ps: PortStateUpdates) => Right(Option(ps))
        case _ => Right(None)
      }
      .recover {
        case t => Left(PortStateError(t.getMessage))
      }
  }
}
