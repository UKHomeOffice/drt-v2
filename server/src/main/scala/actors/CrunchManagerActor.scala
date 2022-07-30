package actors

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.{Actor, ActorRef}

class CrunchManagerActor extends Actor {
  var maybeRequestActor: Option[ActorRef] = None

  override def receive: Receive = {
    case SetCrunchRequestQueue(requestActor) =>
      maybeRequestActor = Option(requestActor)

    case um: UpdatedMillis =>
      maybeRequestActor.foreach(_ ! um)
  }
}
