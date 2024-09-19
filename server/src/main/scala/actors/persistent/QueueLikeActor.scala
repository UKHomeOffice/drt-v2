package actors.persistent

import akka.persistence._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands._
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState._
import uk.gov.homeoffice.drt.protobuf.serialisation.CrunchRequestMessageConversion.{terminalUpdateRequestToMessage, terminalUpdateRequestsFromMessage}
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor


case object GetArrivals

object QueueLikeActor {
  case object Tick
}

abstract class QueueLikeActor(val now: () => SDateLike, terminals: Iterable[Terminal]) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val state: mutable.SortedSet[TerminalUpdateRequest] = mutable.SortedSet()

  private val requestsFromMessage: CrunchRequestMessage => Seq[TerminalUpdateRequest] = terminalUpdateRequestsFromMessage(terminals)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      state ++= requests.flatMap(requestsFromMessage)

    case _: MergeArrivalsRequestsMessage =>
      log.info("Ignoring MergeArrivalsRequestsMessage")

    case RemoveCrunchRequestMessage(Some(year), Some(month), Some(day), maybeTerminal) =>
      state
        .find { case TerminalUpdateRequest(terminal, localDate) =>
          localDate == LocalDate(year, month, day) && Option(terminal) == maybeTerminal.map(Terminal(_))
        }
        .foreach(state -= _)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      state ++= requests
        .map(requestsFromMessage)
        .collect {
          case r: TerminalUpdateRequest => r
        }
  }

  override def stateToMessage: GeneratedMessage =
    CrunchRequestsMessage(state.toList.collect {
      case cr: TerminalUpdateRequest => terminalUpdateRequestToMessage(cr)
    })

  override def receiveCommand: Receive = {
    case GetState =>
      sender() ! state

    case cr: TerminalUpdateRequest =>
      self ! Seq(cr)

    case requests: Iterable[TerminalUpdateRequest] =>
      val newRequests = requests.filterNot(state.contains)
      val messages = newRequests.map(terminalUpdateRequestToMessage)
      persistAndMaybeSnapshot(CrunchRequestsMessage(messages.toSeq))
      updateState(newRequests)

    case RemoveProcessingRequest(cr) =>
      log.info(s"Removing ${cr.date} from queue. Queue now contains ${state.size} days")
      state -= cr
      persistAndMaybeSnapshot(RemoveCrunchRequestMessage(
        year = Option(cr.date.year),
        month = Option(cr.date.month),
        day = Option(cr.date.day),
        terminalName = None,
      ))

    case _: SaveSnapshotSuccess =>
      log.info(s"Successfully saved snapshot")

    case _: DeleteSnapshotSuccess =>
      log.info(s"Successfully deleted snapshot")

    case u =>
      log.error(s"Unexpected message: ${u.getClass}")
  }

  def updateState(days: Iterable[TerminalUpdateRequest]): Unit = {
    state ++= days
    log.info(s"Adding ${days.size} days to queue. Queue now contains ${state.size} days")
  }

}
