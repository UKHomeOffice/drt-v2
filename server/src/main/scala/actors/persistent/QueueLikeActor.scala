package actors.persistent

import actors.persistent.staffing.GetState
import actors.serializers.CrunchRequestMessageConversion.{crunchRequestToMessage, crunchRequestsFromMessages}
import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchRequestsMessage, DaysMessage, RemoveCrunchRequestMessage, RemoveDayMessage}
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.{CrunchRequest, RemoveCrunchRequest}
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor


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

  val state: mutable.SortedSet[CrunchRequest] = mutable.SortedSet()

  def crunchRequestFromMillis(millis: MillisSinceEpoch): CrunchRequest =
    CrunchRequest(SDate(millis).toLocalDate, crunchOffsetMinutes, durationMinutes)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      state ++= crunchRequestsFromMessages(requests)
    case RemoveCrunchRequestMessage(Some(year), Some(month), Some(day), _) =>
      state.find(_.localDate == LocalDate(year, month, day)).foreach {
        state -= _
      }

    case DaysMessage(days) => state ++= days.map(crunchRequestFromMillis)
    case RemoveDayMessage(Some(day)) => state -= crunchRequestFromMillis(day)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      state ++= crunchRequestsFromMessages(requests).to[mutable.SortedSet]

    case DaysMessage(days) =>
      log.info(s"Restoring queue to ${days.size} days")
      state ++= days.map(crunchRequestFromMillis).to[mutable.SortedSet]
  }

  override def stateToMessage: GeneratedMessage =
    CrunchRequestsMessage(state.map(crunchRequestToMessage).toList)

  override def receiveCommand: Receive = {
    case GetState =>
      sender() ! state

    case cr: CrunchRequest =>
      updateState(Seq(cr))
      persistAndMaybeSnapshot(CrunchRequestsMessage(List(crunchRequestToMessage(cr))))

    case RemoveCrunchRequest(cr) =>
      state -= cr
      persistAndMaybeSnapshot(CrunchRequestsMessage(List(crunchRequestToMessage(cr))))

    case _: SaveSnapshotSuccess =>
      log.info(s"Successfully saved snapshot")

    case _: DeleteSnapshotSuccess =>
      log.info(s"Successfully deleted snapshot")

    case u =>
      log.warn(s"Unexpected message: ${u.getClass}")
  }

  def updateState(days: Iterable[CrunchRequest]): Unit = {
    state ++= days
    log.info(s"Adding ${days.size} days to queue. Queue now contains ${state.size} days")
  }

}
