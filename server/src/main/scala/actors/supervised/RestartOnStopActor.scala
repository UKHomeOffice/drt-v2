package actors.supervised

import org.apache.pekko.actor.{Actor, ActorRef}
import org.apache.pekko.pattern.{BackoffOnStopOptions, BackoffSupervisor}


class RestartOnStopActor(backoffOnStopOptions: BackoffOnStopOptions) extends Actor {
  val protectedActor: ActorRef = context.actorOf(BackoffSupervisor.props(backoffOnStopOptions))

  override def receive: Receive = {
    case toPassOn => protectedActor.forward(toPassOn)
  }
}
