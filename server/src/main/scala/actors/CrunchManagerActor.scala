package actors

import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber, RecalculateArrivals, Recrunch}
import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.{Actor, ActorRef}

object CrunchManagerActor {
  case class AddQueueCrunchSubscriber(subscriber: ActorRef)

  case class AddRecalculateArrivalsSubscriber(subscriber: ActorRef)

  trait ReProcessDates {
    val updatedMillis: UpdatedMillis
  }

  case class RecalculateArrivals(updatedMillis: UpdatedMillis) extends ReProcessDates

  case class Recrunch(updatedMillis: UpdatedMillis) extends ReProcessDates
}

class CrunchManagerActor extends Actor {
  private var maybeQueueCrunchSubscriber: Option[ActorRef] = None
  private var maybeRecalculateArrivalsSubscriber: Option[ActorRef] = None

  override def receive: Receive = {
    case AddQueueCrunchSubscriber(subscriber) =>
      maybeQueueCrunchSubscriber = Option(subscriber)

    case AddRecalculateArrivalsSubscriber(subscriber) =>
      maybeRecalculateArrivalsSubscriber = Option(subscriber)

    case Recrunch(um) =>
      maybeQueueCrunchSubscriber.foreach(_ ! um)

    case RecalculateArrivals(um) =>
      maybeRecalculateArrivalsSubscriber.foreach(_ ! um)
  }
}
