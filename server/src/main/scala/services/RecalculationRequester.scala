package services

import actors.queues.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class RecalculationRequester() {
  val log: Logger = LoggerFactory.getLogger(getClass)

  private var sourceIsReady: Boolean = false
  private var millisBuffer: Set[MillisSinceEpoch] = Set()
  private var maybeQueueActor: Option[ActorRef] = None

  def setQueueActor(actor: ActorRef): Unit = maybeQueueActor = Option(actor)

  def addMillis(updatedMinutes: Iterable[MillisSinceEpoch]): Unit =
    millisBuffer = millisBuffer ++ updatedMinutes

  def handleUpdatedMillis()(implicit ec: ExecutionContext): Unit =
    (maybeQueueActor, millisBuffer.nonEmpty, sourceIsReady) match {
      case (Some(queueActor), true, true) =>
        sourceIsReady = false
        val updatedMillisToSend = millisBuffer
        millisBuffer = Set()
        queueActor
          .ask(UpdatedMillis(updatedMillisToSend))(new Timeout(15 seconds))
          .recover {
            case e =>
              log.error("Error updated minutes to crunch queue actor. Putting the minutes back in the buffer to try again", e)
              millisBuffer = millisBuffer ++ updatedMillisToSend
          }
          .onComplete(_ => sourceIsReady = true)
      case _ =>
    }
}