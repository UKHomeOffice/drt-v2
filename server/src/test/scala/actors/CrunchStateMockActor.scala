package actors

import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import org.apache.pekko.actor.Actor
import org.apache.pekko.pattern.StatusReply
import drt.shared.PortState

class CrunchStateMockActor(initialPortState: Option[PortState]) extends Actor {
  override def receive: Receive = {
    case GetState => sender() ! initialPortState
    case _ => sender() ! StatusReply.Ack
  }
}
