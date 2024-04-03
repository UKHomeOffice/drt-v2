package actors.persistent

import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState._
import uk.gov.homeoffice.drt.protobuf.serialisation.CrunchRequestMessageConversion.{loadProcessingRequestFromMessage, loadProcessingRequestToMessage, mergeArrivalRequestToMessage, mergeArrivalsRequestFromMessage}
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike, UtcDate}

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor


object QueueLikeActor {
  case object Tick
}

abstract class QueueLikeActor(val now: () => SDateLike, processingRequest: MillisSinceEpoch => ProcessingRequest) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val state: mutable.SortedSet[ProcessingRequest] = mutable.SortedSet()

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      state ++= requests.map(loadProcessingRequestFromMessage)

    case MergeArrivalsRequestsMessage(requests) =>
      state ++= requests.map(mergeArrivalsRequestFromMessage)

    case RemoveCrunchRequestMessage(Some(year), Some(month), Some(day), maybeTerminal) => state.find {
      case TerminalUpdateRequest(terminal, localDate, _, _) =>
        localDate == LocalDate(year, month, day) && Option(terminal) == maybeTerminal.map(Terminal(_))
      case CrunchRequest(localDate, _, _) =>
        localDate == LocalDate(year, month, day)
      case MergeArrivalsRequest(utcDate) =>
        utcDate == UtcDate(year, month, day)
    }.foreach(state -= _)

    case DaysMessage(days) => state ++= days.map(processingRequest)
    case RemoveDayMessage(Some(day)) => state -= processingRequest(day)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      state ++= requests.map(loadProcessingRequestFromMessage)

    case MergeArrivalsRequestsMessage(requests) =>
      log.info(s"Restoring queue to ${requests.size} days")
      state ++= requests.map(mergeArrivalsRequestFromMessage)

    case DaysMessage(days) =>
      log.info(s"Restoring queue to ${days.size} days")
      state ++= days.map(processingRequest)
  }

  override def stateToMessage: GeneratedMessage = {
    state.headOption.map {
      case _: CrunchRequest =>
        CrunchRequestsMessage(state.toList.collect {
          case cr: CrunchRequest => loadProcessingRequestToMessage(cr)
        })
      case _: TerminalUpdateRequest =>
        CrunchRequestsMessage(state.toList.collect {
          case cr: TerminalUpdateRequest => loadProcessingRequestToMessage(cr)
        })
      case _: MergeArrivalsRequest =>
        MergeArrivalsRequestsMessage(state.toList.collect {
          case mar: MergeArrivalsRequest => mergeArrivalRequestToMessage(mar)
        })
    }.getOrElse(CrunchRequestsMessage(List()))
  }

  override def receiveCommand: Receive = {
    case GetState =>
      sender() ! state

    case cr: ProcessingRequest =>
      self ! Seq(cr)

    case requests: Iterable[_] =>
      requests.headOption
        .map {
          case _: Long =>
            requests
              .collect { case l: MillisSinceEpoch => processingRequest(l) }
              .filterNot(state.contains)
          case _: ProcessingRequest =>
            requests
              .collect {
                case r: ProcessingRequest =>
                  println(s"Received $r: ${r.date}: ${r.start.toISOString} ${r.end.toISOString}")
                  r
              }
              .filterNot(state.contains)
        }
        .map { processingRequests =>
          val maybeMessage = processingRequests.headOption.map {
            case _: LoadProcessingRequest =>
              CrunchRequestsMessage(processingRequests.collect {
                case r: LoadProcessingRequest => loadProcessingRequestToMessage(r)
              }.toList)
            case _: MergeArrivalsRequest =>
              MergeArrivalsRequestsMessage(processingRequests.collect {
                case r: MergeArrivalsRequest => mergeArrivalRequestToMessage(r)
              }.toList)
          }
          maybeMessage.foreach { msg =>
            persistAndMaybeSnapshot(msg)
            updateState(processingRequests)
          }

        }

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

  def updateState(days: Iterable[ProcessingRequest]): Unit = {
    state ++= days
    log.info(s"Adding ${days.size} days to queue. Queue now contains ${state.size} days")
  }

}
