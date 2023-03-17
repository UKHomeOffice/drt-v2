package actors.persistent

import actors.persistent.staffing.GetState
import actors.serializers.CrunchRequestMessageConversion.{crunchRequestToMessage, crunchRequestsFromMessages}
import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.crunch.deskrecs.RunnableOptimisation.{CrunchRequest, ProcessingRequest, RemoveCrunchRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.DataUpdates.Combinable
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchRequestsMessage, DaysMessage, RemoveCrunchRequestMessage, RemoveDayMessage}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor


object QueueLikeActor {

  case object Tick

  object UpdatedMillis {
    val empty: UpdatedMillis = UpdatedMillis(Set())
  }

  case class UpdatedMillis(effects: Set[MillisSinceEpoch]) extends Combinable[UpdatedMillis] {
    def ++(other: UpdatedMillis): UpdatedMillis = other match {
      case UpdatedMillis(toAdd) => UpdatedMillis(effects ++ toAdd)
      case _ => this
    }
  }

}

abstract class QueueLikeActor(val now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val state: mutable.SortedSet[ProcessingRequest] = mutable.SortedSet()

  private def crunchRequestFromMillis(millis: MillisSinceEpoch): ProcessingRequest =
    CrunchRequest(SDate(millis).toLocalDate, crunchOffsetMinutes, durationMinutes)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      state ++= crunchRequestsFromMessages(requests)

    case RemoveCrunchRequestMessage(Some(year), Some(month), Some(day), maybeTerminal) => state.find {
      case TerminalUpdateRequest(terminal, localDate, _, _) =>
        localDate == LocalDate(year, month, day) && Option(terminal) == maybeTerminal.map(Terminal(_))
      case CrunchRequest(localDate, _, _) =>
        localDate == LocalDate(year, month, day)
    }.foreach(state -= _)

    case DaysMessage(days) => state ++= days.map(crunchRequestFromMillis)
    case RemoveDayMessage(Some(day)) => state -= crunchRequestFromMillis(day)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      state ++= crunchRequestsFromMessages(requests)

    case DaysMessage(days) =>
      log.info(s"Restoring queue to ${days.size} days")
      state ++= days.map(crunchRequestFromMillis)
  }

  override def stateToMessage: GeneratedMessage =
    CrunchRequestsMessage(state.toList.map(crunchRequestToMessage))

  override def receiveCommand: Receive = {
    case GetState =>
      sender() ! state

    case cr: CrunchRequest =>
      updateState(Seq(cr))
      persistAndMaybeSnapshot(CrunchRequestsMessage(List(crunchRequestToMessage(cr))))

    case tur: TerminalUpdateRequest =>
      updateState(Seq(tur))
      persistAndMaybeSnapshot(CrunchRequestsMessage(List(crunchRequestToMessage(tur))))

    case RemoveCrunchRequest(cr) =>
      log.info(s"Removing ${cr.localDate} from queue. Queue now contains ${state.size} days")
      state -= cr
      persistAndMaybeSnapshot(RemoveCrunchRequestMessage(
        year = Option(cr.localDate.year),
        month = Option(cr.localDate.month),
        day = Option(cr.localDate.day),
        terminalName = None))

    case _: SaveSnapshotSuccess =>
      log.info(s"Successfully saved snapshot")

    case _: DeleteSnapshotSuccess =>
      log.info(s"Successfully deleted snapshot")

    case u =>
      log.warn(s"Unexpected message: ${u.getClass}")
  }

  def updateState(days: Iterable[ProcessingRequest]): Unit = {
    state ++= days
    log.info(s"Adding ${days.size} $days days to queue. Queue now contains ${state.size} days")
  }

}
