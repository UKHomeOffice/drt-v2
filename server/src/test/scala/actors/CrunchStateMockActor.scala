package actors

import actors.acking.AckingReceiver.Ack
import actors.persistent.staffing.GetState
import akka.actor.Actor
import drt.shared.PortState

class CrunchStateMockActor(initialPortState: Option[PortState]) extends Actor {
  override def receive: Receive = {
    case GetState => sender() ! initialPortState
    case _ => sender() ! Ack
  }
}
