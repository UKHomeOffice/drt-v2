package actors.serializers

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchRequestMessage, RemoveCrunchRequestMessage}
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import uk.gov.homeoffice.drt.time.LocalDate

object CrunchRequestMessageConversion {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def removeCrunchRequestMessage(request: CrunchRequest): RemoveCrunchRequestMessage = {
    val localDate = request.localDate
    RemoveCrunchRequestMessage(Option(localDate.year), Option(localDate.month), Option(localDate.day))
  }

  def crunchRequestToMessage(cr: CrunchRequest): CrunchRequestMessage = CrunchRequestMessage(
    Option(cr.localDate.year),
    Option(cr.localDate.month),
    Option(cr.localDate.day),
    Option(cr.offsetMinutes),
    Option(cr.durationMinutes)
  )

  def crunchRequestsFromMessages(requests: Iterable[CrunchRequestMessage]): Iterable[CrunchRequest] = requests
    .map(maybeCrunchRequestFromMessage)
    .collect { case Some(cr) => cr }

  def maybeCrunchRequestFromMessage: CrunchRequestMessage => Option[CrunchRequest] = {
    case CrunchRequestMessage(Some(year), Some(month), Some(day), Some(offsetMinutes), Some(durationMinutes), _) =>
      Some(CrunchRequest(LocalDate(year, month, day), offsetMinutes, durationMinutes))

    case badMessage =>
      log.warn(s"Can't convert to CrunchRequest: $badMessage")
      None
  }
}
