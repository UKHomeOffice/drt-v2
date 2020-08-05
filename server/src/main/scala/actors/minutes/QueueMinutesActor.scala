package actors.minutes

import actors.SetDeploymentQueueActor
import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.queues.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}

import scala.concurrent.Future
import scala.language.postfixOps

class QueueMinutesActor(terminals: Iterable[Terminal],
                        lookup: MinutesLookup[CrunchMinute, TQM],
                        updateMinutes: MinutesUpdate[CrunchMinute, TQM])
  extends MinutesActorLike(terminals, lookup, updateMinutes) {

  var maybeUpdatesSubscriber: Option[ActorRef] = None

  override def handleUpdatesAndAck(container: MinutesContainer[CrunchMinute, TQM],
                                   replyTo: ActorRef): Future[Option[MinutesContainer[CrunchMinute, TQM]]] = {
    val eventualUpdatesDiff = super.handleUpdatesAndAck(container, replyTo)
    val gotDeskRecs = container.contains(classOf[DeskRecMinute])

    if (gotDeskRecs) sendUpdatedMillisToSubscriber(eventualUpdatesDiff)

    eventualUpdatesDiff
  }

  private def sendUpdatedMillisToSubscriber(eventualUpdatesDiff: Future[Option[MinutesContainer[CrunchMinute, TQM]]]): Future[Unit] = eventualUpdatesDiff.collect {
    case Some(diffMinutesContainer) =>
      val updatedMillis = diffMinutesContainer.minutes.collect { case m: CrunchMinute => m.minute }
      maybeUpdatesSubscriber.foreach(_ ! UpdatedMillis(updatedMillis))
  }

  override def receive: Receive = deploymentReceives orElse super.receive

  def deploymentReceives: Receive = {
    case SetDeploymentQueueActor(subscriber) =>
      log.info(s"Received subscriber actor")
      maybeUpdatesSubscriber = Option(subscriber)
  }
}
