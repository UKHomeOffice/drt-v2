package actors.supervised

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{BackoffOnStopOptions, BackoffOpts, BackoffSupervisor}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class RestartOnStopActor(backoffOnStopOptions: BackoffOnStopOptions) extends Actor {
  val protectedActor: ActorRef = context.actorOf(BackoffSupervisor.props(backoffOnStopOptions))

  override def receive: Receive = {
    case toPassOn => protectedActor.forward(toPassOn)
  }
}

case class RestartOnStop(minBackoff: FiniteDuration, maxBackoff: FiniteDuration) {
  def actorOf(props: Props, name: String)(implicit system: ActorSystem): ActorRef = {
    val onStopOptions = BackoffOpts.onStop(
      childProps = props,
      childName = name,
      minBackoff = minBackoff,
      maxBackoff = maxBackoff,
      randomFactor = 0
    )
    system.actorOf(Props(classOf[RestartOnStopActor], onStopOptions), s"$name-supervisor")
  }
}
