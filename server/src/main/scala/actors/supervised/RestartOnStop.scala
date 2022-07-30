package actors.supervised

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.BackoffOpts

import scala.concurrent.duration.FiniteDuration


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
