package actors

import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber, RecalculateArrivals}
import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.{Actor, ActorRef}
import akka.stream.scaladsl.SourceQueueWithComplete

object CrunchManagerActor {
  case class AddQueueCrunchSubscriber(subscriber: ActorRef)

  case class AddRecalculateArrivalsSubscriber(subscribeResponseQueue: SourceQueueWithComplete[Boolean])

  case object RecalculateArrivals
}

class CrunchManagerActor extends Actor {
  private var maybeQueueCrunchSubscriber: Option[ActorRef] = None
  private var maybeRecalculateArrivalsSubscriber: Option[SourceQueueWithComplete[Boolean]] = None

  override def receive: Receive = {
    case AddQueueCrunchSubscriber(subscriber) =>
      maybeQueueCrunchSubscriber = Option(subscriber)

    case AddRecalculateArrivalsSubscriber(subscriber) =>
      maybeRecalculateArrivalsSubscriber = Option(subscriber)

    case um: UpdatedMillis =>
      maybeQueueCrunchSubscriber.foreach(_ ! um)

    case RecalculateArrivals =>
      maybeRecalculateArrivalsSubscriber.foreach(_.offer(true))
  }
}
