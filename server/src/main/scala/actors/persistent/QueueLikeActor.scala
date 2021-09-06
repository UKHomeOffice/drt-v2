package actors.persistent

import actors.persistent.staffing.GetState
import actors.serializers.CrunchRequestMessageConversion.{crunchRequestToMessage, crunchRequestsFromMessages}
import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.dates.LocalDate
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchRequestsMessage, DaysMessage, RemoveCrunchRequestMessage, RemoveDayMessage}
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.{CrunchRequest, RemoveCrunchRequest}

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.language.postfixOps


object QueueLikeActor {

  case object Tick

  case object ReadyToEmit

  object UpdatedMillis {
    val empty: UpdatedMillis = UpdatedMillis(Seq())
  }

  case class UpdatedMillis(effects: Iterable[MillisSinceEpoch]) {
    def ++(other: UpdatedMillis): UpdatedMillis = other match {
      case UpdatedMillis(toAdd) => UpdatedMillis(effects ++ toAdd)
      case _ => this
    }
  }

}

abstract class QueueLikeActor(val now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val queuedDays: mutable.SortedSet[CrunchRequest] = mutable.SortedSet()

  def crunchRequestFromMillis(millis: MillisSinceEpoch): CrunchRequest =
    CrunchRequest(SDate(millis).toLocalDate, crunchOffsetMinutes, durationMinutes)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      queuedDays ++= crunchRequestsFromMessages(requests)
    case RemoveCrunchRequestMessage(Some(year), Some(month), Some(day)) =>
      queuedDays.find(_.localDate == LocalDate(year, month, day)).foreach {
        queuedDays -= _
      }

    case DaysMessage(days) => queuedDays ++= days.map(crunchRequestFromMillis)
    case RemoveDayMessage(Some(day)) => queuedDays -= crunchRequestFromMillis(day)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      queuedDays ++= crunchRequestsFromMessages(requests).to[mutable.SortedSet]

    case DaysMessage(days) =>
      log.info(s"Restoring queue to ${days.size} days")
      queuedDays ++= days.map(crunchRequestFromMillis).to[mutable.SortedSet]
  }

  override def stateToMessage: GeneratedMessage =
    CrunchRequestsMessage(queuedDays.map(crunchRequestToMessage).toList)

  override def receiveCommand: Receive = {
    case GetState =>
      sender() ! queuedDays

    case cr: CrunchRequest =>
      println(s"\n\n**$persistenceId Received crunch request: $cr")
      updateState(Seq(cr))
      persistAndMaybeSnapshot(CrunchRequestsMessage(List(crunchRequestToMessage(cr))))

    case RemoveCrunchRequest(cr) =>
      println(s"\n\n**$persistenceId Received remove crunch request: $cr")
      queuedDays -= cr
      persistAndMaybeSnapshot(CrunchRequestsMessage(List(crunchRequestToMessage(cr))))

    case _: SaveSnapshotSuccess =>
      log.info(s"Successfully saved snapshot")

    case _: DeleteSnapshotSuccess =>
      log.info(s"Successfully deleted snapshot")

    case u =>
      log.warn(s"Unexpected message: ${u.getClass}")
  }

  def updateState(days: Iterable[CrunchRequest]): Unit = {
    log.info(s"Adding ${days.size} days to queue. Queue now contains ${queuedDays.size} days")
    queuedDays ++= days
  }

}
