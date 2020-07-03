package actors

import actors.acking.AckingReceiver.Ack
import akka.actor.Actor
import drt.shared.PortState

class CrunchStateMockActor(initialPortState: Option[PortState]) extends Actor {
  override def receive: Receive = {
    case GetState =>
      println(s"\n\nGot GetState, replying with $initialPortState\n\n")
      sender() ! initialPortState
    case _ => sender() ! Ack
  }
}
