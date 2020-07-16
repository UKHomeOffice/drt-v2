package actors.minutes

import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.{HandleRecalculations, SetDeploymentQueueActor}
import akka.actor.{ActorRef, Cancellable}
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}
import services.RecalculationRequester

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class QueueMinutesActor(now: () => SDateLike,
                        terminals: Iterable[Terminal],
                        lookup: MinutesLookup[CrunchMinute, TQM],
                        lookupLegacy: MinutesLookup[CrunchMinute, TQM],
                        updateMinutes: MinutesUpdate[CrunchMinute, TQM]) extends MinutesActorLike(now, terminals, lookup, lookupLegacy, updateMinutes) {

  val reDeployHandler = new RecalculationRequester()

  val cancellableTick: Cancellable = context.system.scheduler.schedule(10 seconds, 500 millisecond, self, HandleRecalculations)

  override def postStop(): Unit = {
    log.warn("Actor stopped. Cancelling scheduled tick")
    cancellableTick.cancel()
    super.postStop()
  }

  override def handleUpdatesAndAck(container: MinutesContainer[CrunchMinute, TQM],
                                   replyTo: ActorRef): Future[Option[MinutesContainer[CrunchMinute, TQM]]] = {
    val eventualUpdatesDiff = super.handleUpdatesAndAck(container, replyTo)
    val gotDeskRecs = container.contains(classOf[DeskRecMinute])

    if (gotDeskRecs) addUpdatesToBufferAndSendToSubscriber(eventualUpdatesDiff)

    eventualUpdatesDiff
  }

  private def addUpdatesToBufferAndSendToSubscriber(eventualUpdatesDiff: Future[Option[MinutesContainer[CrunchMinute, TQM]]]): Future[Unit] = eventualUpdatesDiff.collect {
    case Some(diffMinutesContainer) =>
      val updatedMillis = diffMinutesContainer.minutes.collect { case m: CrunchMinute => m.minute }
      reDeployHandler.addMillis(updatedMillis.toSeq)
  }

  override def receive: Receive = deploymentReceives orElse super.receive

  def deploymentReceives: Receive = {
    case SetDeploymentQueueActor(subscriber) =>
      log.info(s"Received subscriber actor")
      reDeployHandler.setQueueActor(subscriber)

    case HandleRecalculations => reDeployHandler.handleUpdatedMillis()
  }
}
