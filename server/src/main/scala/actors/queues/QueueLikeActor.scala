package actors.queues

import actors.queues.QueueLikeActor.{ReadyToEmit, Tick, UpdatedMillis}
import actors.{RecoveryActorLike, SetCrunchRequestQueue}
import akka.actor.Cancellable
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.dates.LocalDate
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchRequestMessage, CrunchRequestsMessage, DaysMessage, RemoveCrunchRequestMessage, RemoveDayMessage}
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps


object QueueLikeActor {

  case object Tick

  case object ReadyToEmit

  case class UpdatedMillis(millis: Iterable[MillisSinceEpoch])

}

abstract class QueueLikeActor(val now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val cancellableTick: Cancellable = context.system.scheduler.schedule(1 second, 1 second, self, Tick)

  var maybeDaysQueueSource: Option[SourceQueueWithComplete[CrunchRequest]] = None
  var queuedDays: SortedSet[CrunchRequest] = SortedSet[CrunchRequest]()
  var readyToEmit: Boolean = false

  override def postStop(): Unit = {
    log.info(s"I've stopped so I'll cancel my tick now")
    cancellableTick.cancel()
    super.postStop()
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      queuedDays = queuedDays ++ crunchRequestsFromMessages(requests)
    case DaysMessage(days) => queuedDays = queuedDays ++ days.map(crunchRequestFromMillis)
    case RemoveDayMessage(Some(day)) => queuedDays = queuedDays - crunchRequestFromMillis(day)
  }

  private def crunchRequestsFromMessages(requests: Seq[CrunchRequestMessage]): Seq[CrunchRequest] = requests.map {
    case CrunchRequestMessage(Some(year), Some(month), Some(day)) =>
      crunchRequestFromMessage(year, month, day)
  }

  private def crunchRequestFromMessage(year: Int, month: Int, day: Int): CrunchRequest = {
    CrunchRequest(LocalDate(year, month, day), crunchOffsetMinutes, durationMinutes)
  }

  private def crunchRequestFromMillis(millis: MillisSinceEpoch): CrunchRequest = {
    CrunchRequest(SDate(millis).toLocalDate, crunchOffsetMinutes, durationMinutes)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      queuedDays = crunchRequestsFromMessages(requests).to[SortedSet]
    case DaysMessage(days) =>
      log.info(s"Restoring queue to ${days.size} days")
      queuedDays = days.map(crunchRequestFromMillis).to[SortedSet]
  }

  override def stateToMessage: GeneratedMessage = CrunchRequestsMessage(queuedDays.map(cr =>
    crunchRequestToMessage(cr)).toList)

  private def crunchRequestToMessage(cr: CrunchRequest) = {
    CrunchRequestMessage(Option(cr.localDate.year), Option(cr.localDate.month), Option(cr.localDate.day))
  }

  override def receiveCommand: Receive = {
    case Tick =>
      log.debug(s"Got a tick. Will try to emit if I'm able to")
      emitNextDayIfReady()

    case ReadyToEmit =>
      readyToEmit = true
      emitNextDayIfReady()

    case SetCrunchRequestQueue(source) =>
      log.info(s"Received daysQueueSource")
      maybeDaysQueueSource = Option(source)
      readyToEmit = true
      emitNextDayIfReady()

    case UpdatedMillis(millis) =>
      log.info(s"Received ${millis.size} UpdatedMillis")
      val requests = millis.map(CrunchRequest(_, crunchOffsetMinutes, durationMinutes))
      updateState(requests)
      emitNextDayIfReady()
      persistAndMaybeSnapshot(CrunchRequestsMessage(requests.map(crunchRequestToMessage).toList))

    case _: SaveSnapshotSuccess =>
      log.info(s"Successfully saved snapshot")

    case _: DeleteSnapshotSuccess =>
      log.info(s"Successfully deleted snapshot")

    case u =>
      log.warn(s"Unexpected message: ${u.getClass}")
  }

  def emitNextDayIfReady(): Unit = if (readyToEmit)
    queuedDays.headOption match {
      case Some(request) =>
        readyToEmit = false
        maybeDaysQueueSource.foreach { sourceQueue =>
          sourceQueue.offer(request).foreach { _ =>
            self ! ReadyToEmit
          }
        }
        queuedDays = queuedDays.drop(1)
        persistAndMaybeSnapshot(removeCrunchRequestMessage(request))
      case None =>
        log.debug(s"Nothing in the queue to emit")
    }

  private def removeCrunchRequestMessage(request: CrunchRequest) = {
    val localDate = request.localDate
    val message = RemoveCrunchRequestMessage(Option(localDate.year), Option(localDate.month), Option(localDate.day))
    message
  }

  def updateState(days: Iterable[CrunchRequest]): Unit = {
    log.info(s"Adding ${days.size} days to queue. Queue now contains ${queuedDays.size} days")
    queuedDays = queuedDays ++ days
  }

}
