package actors.supervised

import akka.actor.{Actor, ActorRef}
import akka.pattern.{BackoffOnStopOptions, BackoffSupervisor}

class RestartOnStopActor(backoffOnStopOptions: BackoffOnStopOptions) extends Actor {
  val protectedActor: ActorRef = context.actorOf(BackoffSupervisor.props(backoffOnStopOptions))

  override def receive: Receive = {
    case toPassOn => protectedActor.forward(toPassOn)
  }
}
