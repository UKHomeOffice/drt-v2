package actors.serializers

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchRequestMessage, RemoveCrunchRequestMessage}
import uk.gov.homeoffice.drt.time.LocalDate

object CrunchRequestMessageConversion {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def removeCrunchRequestMessage(request: CrunchRequest): RemoveCrunchRequestMessage = {
    val localDate = request.localDate
    RemoveCrunchRequestMessage(Option(localDate.year), Option(localDate.month), Option(localDate.day))
  }

  def crunchRequestToMessage(cr: ProcessingRequest): CrunchRequestMessage = {
    val maybeTerminalName = cr match {
      case _: CrunchRequest => None
      case tur: TerminalUpdateRequest => Option(tur.terminal.toString)
    }
    CrunchRequestMessage(
      Option(cr.localDate.year),
      Option(cr.localDate.month),
      Option(cr.localDate.day),
      Option(cr.offsetMinutes),
      Option(cr.durationMinutes),
      maybeTerminalName,
    )
  }

  def crunchRequestsFromMessages(requests: Iterable[CrunchRequestMessage]): Iterable[ProcessingRequest] = requests
    .map(maybeCrunchRequestFromMessage)
    .collect { case Some(cr) => cr }

  def maybeCrunchRequestFromMessage: CrunchRequestMessage => Option[ProcessingRequest] = {
    case CrunchRequestMessage(Some(year), Some(month), Some(day), Some(offsetMinutes), Some(durationMinutes), maybeTerminalName) =>
      maybeTerminalName match {
        case None =>
          Some(CrunchRequest(LocalDate(year, month, day), offsetMinutes, durationMinutes))
        case Some(terminalName) =>
          Some(TerminalUpdateRequest(Terminal(terminalName), LocalDate(year, month, day), offsetMinutes, durationMinutes))
      }

    case badMessage =>
      log.warn(s"Can't convert to CrunchRequest: $badMessage")
      None
  }
}
