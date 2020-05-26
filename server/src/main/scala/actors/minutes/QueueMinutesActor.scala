package actors.minutes

import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.{HandleSimulationRequest, SetSimulationActor, SetSimulationSourceReady}
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.{SDateLike, TQM}
import drt.shared.Terminals.Terminal
import services.graphstages.Crunch.{LoadMinute, Loads}

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

class QueueMinutesActor(now: () => SDateLike,
                        terminals: Iterable[Terminal],
                        lookupPrimary: MinutesLookup[CrunchMinute, TQM],
                        lookupSecondary: MinutesLookup[CrunchMinute, TQM],
                        updateMinutes: MinutesUpdate[CrunchMinute, TQM]) extends MinutesActorLike(now, terminals, lookupPrimary, lookupSecondary, updateMinutes) {

  val minutesBuffer: mutable.Map[TQM, LoadMinute] = mutable.Map[TQM, LoadMinute]()
  var maybeUpdateSubscriber: Option[ActorRef] = None
  var subscriberIsReady: Boolean = false

  override def handleUpdatesAndAck(container: MinutesContainer[CrunchMinute, TQM],
                                   replyTo: ActorRef): Future[Option[MinutesContainer[CrunchMinute, TQM]]] = {
    val eventualUpdatesDiff = super.handleUpdatesAndAck(container, replyTo)
    val gotDeskRecs = container.contains(classOf[DeskRecMinute])

    if (maybeUpdateSubscriber.isDefined && gotDeskRecs) addUpdatesToBufferAndSendToSubscriber(eventualUpdatesDiff)

    eventualUpdatesDiff
  }

  private def addUpdatesToBufferAndSendToSubscriber(eventualUpdatesDiff: Future[Option[MinutesContainer[CrunchMinute, TQM]]]): Future[Unit] = eventualUpdatesDiff.collect {
    case Some(diffMinutesContainer) =>
      val updatedLoads = diffMinutesContainer.minutes.collect { case m: CrunchMinute =>
        (m.key, LoadMinute(m))
      }
      minutesBuffer ++= updatedLoads
      sendToSubscriber()
  }

  private def sendToSubscriber(): Unit = (maybeUpdateSubscriber, minutesBuffer.nonEmpty, subscriberIsReady) match {
    case (Some(simActor), true, true) =>
      log.info(s"Sending (${minutesBuffer.size}) minutes from buffer to subscriber")
      subscriberIsReady = false
      val loads = Loads(minutesBuffer.values.toList)
      simActor
        .ask(loads)(new Timeout(10 minutes))
        .recover {
          case t => log.error("Error sending loads to simulate", t)
        }
        .onComplete { _ =>
          context.self ! SetSimulationSourceReady
        }
      minutesBuffer.clear()
    case _ =>
      log.info(s"Not sending (${minutesBuffer.size}) minutes from buffer to subscriber")
  }

  override def receive: Receive = simulationReceives orElse super.receive

  def simulationReceives: Receive = {
    case SetSimulationActor(subscriber) =>
      log.info(s"Received subscriber actor")
      maybeUpdateSubscriber = Option(subscriber)
      subscriberIsReady = true

    case SetSimulationSourceReady =>
      subscriberIsReady = true
      context.self ! HandleSimulationRequest

    case HandleSimulationRequest =>
      sendToSubscriber()

  }
}
