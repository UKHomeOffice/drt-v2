package actors

import akka.actor.Actor

class NoopActor extends Actor {
  def receive: Receive = {
    case _ => // do nothing
  }
}
