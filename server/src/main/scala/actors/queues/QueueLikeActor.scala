package actors.queues

import actors.queues.QueueLikeActor.{ReadyToEmit, Tick, UpdatedMillis}
import actors.{RecoveryActorLike, SetDaysQueueSource, StreamingJournalLike}
import akka.actor.Cancellable
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{DaysMessage, RemoveDayMessage}
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps


object QueueLikeActor {

  case object Tick

  case object ReadyToEmit

  case class UpdatedMillis(millis: Iterable[MillisSinceEpoch])

}

abstract class QueueLikeActor(val now: () => SDateLike, crunchOffsetMinutes: Int) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val cancellableTick: Cancellable = context.system.scheduler.schedule(1 second, 1 second, self, Tick)

  val crunchStartFn: SDateLike => SDateLike = Crunch.crunchStartWithOffset(crunchOffsetMinutes)
  var maybeDaysQueueSource: Option[SourceQueueWithComplete[MillisSinceEpoch]] = None
  var queuedDays: SortedSet[MillisSinceEpoch] = SortedSet[MillisSinceEpoch]()
  var readyToEmit: Boolean = false

  override def postStop(): Unit = {
    log.info(s"I've stopped so I'll cancel my tick now")
    cancellableTick.cancel()
    super.postStop()
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case DaysMessage(days) => queuedDays = queuedDays ++ days
    case RemoveDayMessage(Some(day)) => queuedDays = queuedDays - day
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case DaysMessage(days) =>
      log.info(s"Restoring queue to ${days.size} days")
      queuedDays = SortedSet[MillisSinceEpoch]() ++ days
  }

  override def stateToMessage: GeneratedMessage = DaysMessage(queuedDays.toList)

  override def receiveCommand: Receive = {
    case Tick =>
      log.debug(s"Got a tick. Will try to emit if I'm able to")
      emitNextDayIfReady()

    case ReadyToEmit =>
      readyToEmit = true
      log.debug(s"Got a ReadyToEmit. Will emit if I have something in the queue")
      emitNextDayIfReady()

    case SetDaysQueueSource(source) =>
      log.info(s"Received daysQueueSource")
      maybeDaysQueueSource = Option(source)
      readyToEmit = true
      emitNextDayIfReady()

    case UpdatedMillis(millis) =>
      log.info(s"Received ${millis.size} UpdatedMillis")
      val days: Set[MillisSinceEpoch] = uniqueDays(millis)
      updateState(days)
      emitNextDayIfReady()
      persistAndMaybeSnapshot(DaysMessage(days.toList))

    case _: SaveSnapshotSuccess =>
      log.info(s"Successfully saved snapshot")

    case _: DeleteSnapshotSuccess =>
      log.info(s"Successfully deleted snapshot")

    case u =>
      log.warn(s"Unexpected message: ${u.getClass}")
  }

  def uniqueDays(millis: Iterable[MillisSinceEpoch]): Set[MillisSinceEpoch] =
    millis.map(m => crunchStartFn(SDate(m)).millisSinceEpoch).toSet

  def emitNextDayIfReady(): Unit = if (readyToEmit)
    queuedDays.headOption match {
      case Some(day) =>
        log.debug(s"Emitting ${SDate(day, Crunch.europeLondonTimeZone).toISODateOnly}")
        readyToEmit = false
        maybeDaysQueueSource.foreach { sourceQueue =>
          sourceQueue.offer(day).foreach { _ =>
            self ! ReadyToEmit
          }
        }
        queuedDays = queuedDays.drop(1)
        persistAndMaybeSnapshot(RemoveDayMessage(Option(day)))
      case None =>
        log.debug(s"Nothing in the queue to emit")
    }

  def updateState(days: Iterable[MillisSinceEpoch]): Unit = {
    log.info(s"Adding ${days.size} days to queue. Queue now contains: ${queuedDays.map(SDate(_).toISODateOnly).mkString(", ")}")
    queuedDays = queuedDays ++ days
  }

}
