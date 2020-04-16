package actors

import actors.acking.AckingReceiver.Ack
import akka.actor.Actor

class CrunchStateMockActor extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Ack
  }
}
