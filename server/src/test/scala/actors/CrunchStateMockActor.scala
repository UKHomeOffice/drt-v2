package actors

import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import akka.actor.Actor
import akka.pattern.StatusReply
import drt.shared.PortState

class CrunchStateMockActor(initialPortState: Option[PortState]) extends Actor {
  override def receive: Receive = {
    case GetState => sender() ! initialPortState
    case _ => sender() ! StatusReply.Ack
  }
}
