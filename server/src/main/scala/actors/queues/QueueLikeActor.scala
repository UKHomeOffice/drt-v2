package actors.queues

import actors.queues.QueueLikeActor.{ReadyToEmit, Tick, UpdatedMillis}
import actors.serializers.CrunchRequestMessageConversion.{crunchRequestToMessage, crunchRequestsFromMessages, removeCrunchRequestMessage}
import actors.{RecoveryActorLike, SetCrunchRequestQueue}
import akka.actor.Cancellable
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.dates.LocalDate
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchRequestsMessage, DaysMessage, RemoveCrunchRequestMessage, RemoveDayMessage}
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps


object QueueLikeActor {

  case object Tick

  case object ReadyToEmit

  trait UpdateEffect {
    def ++(other: UpdateEffect): UpdateEffect
  }

  object UpdatedMillis {
    val empty: UpdatedMillis = UpdatedMillis(Seq())
  }

  case class UpdatedMillis(affects: Iterable[MillisSinceEpoch]) extends UpdateEffect {
    override def ++(other: UpdateEffect): UpdateEffect = other match {
      case UpdatedMillis(toAdd) => UpdatedMillis(affects ++ toAdd)
      case _ => this
    }
  }

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

  def crunchRequestFromMillis(millis: MillisSinceEpoch): CrunchRequest =
    CrunchRequest(SDate(millis).toLocalDate, crunchOffsetMinutes, durationMinutes)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      queuedDays = queuedDays ++ crunchRequestsFromMessages(requests)
    case RemoveCrunchRequestMessage(Some(year), Some(month), Some(day)) =>
      queuedDays = queuedDays.filterNot(cr => cr.localDate == LocalDate(year, month, day))

    case DaysMessage(days) => queuedDays = queuedDays ++ days.map(crunchRequestFromMillis)
    case RemoveDayMessage(Some(day)) => queuedDays = queuedDays - crunchRequestFromMillis(day)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      queuedDays = crunchRequestsFromMessages(requests).to[SortedSet]

    case DaysMessage(days) =>
      log.info(s"Restoring queue to ${days.size} days")
      queuedDays = days.map(crunchRequestFromMillis).to[SortedSet]
  }

  override def stateToMessage: GeneratedMessage =
    CrunchRequestsMessage(queuedDays.map(crunchRequestToMessage).toList)

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
      val requests = millis.map(CrunchRequest(_, crunchOffsetMinutes, durationMinutes)).toSet
      log.info(s"Received UpdatedMillis covering ${requests.size}")
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

  def updateState(days: Iterable[CrunchRequest]): Unit = {
    log.info(s"Adding ${days.size} days to queue. Queue now contains ${queuedDays.size} days")
    queuedDays = queuedDays ++ days
  }

}
