package actors.serializers

import actors.persistent.arrivals.ArrivalsState
import actors.serializers.PortStateMessageConversion.splitMessageToApiSplits
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState._
import server.protobuf.messages.FlightsMessage._
import services.SDate
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, FeedSource, PortCode}

object FlightMessageConversion {
  val log: Logger = LoggerFactory.getLogger(getClass.toString)

  def flightWithSplitsDiffFromMessage(diffMessage: FlightsWithSplitsDiffMessage): FlightsWithSplitsDiff =
    FlightsWithSplitsDiff(diffMessage.updates.map(flightWithSplitsFromMessage).toList, uniqueArrivalsFromMessages(diffMessage.removals))

  def uniqueArrivalToMessage(unique: UniqueArrival): UniqueArrivalMessage =
    UniqueArrivalMessage(Option(unique.number), Option(unique.terminal.toString), Option(unique.scheduled), Option(unique.origin.toString))

  def flightWithSplitsDiffToMessage(diff: FlightsApi.FlightsWithSplitsDiff): FlightsWithSplitsDiffMessage = {
    FlightsWithSplitsDiffMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      removals = diff.arrivalsToRemove.map {
        case UniqueArrival(number, terminal, scheduled, origin) =>
          UniqueArrivalMessage(Option(number), Option(terminal.toString), Option(scheduled), Option(origin.toString))
        case LegacyUniqueArrival(number, terminal, scheduled) =>
          UniqueArrivalMessage(Option(number), Option(terminal.toString), Option(scheduled), None)
      }.toSeq,
      updates = diff.flightsToUpdate.map(flightWithSplitsToMessage).toSeq
    )
  }

  def uniqueArrivalsFromMessages(uniqueArrivalMessages: Seq[UniqueArrivalMessage]): Seq[UniqueArrivalLike] =
    uniqueArrivalMessages.collect {
      case UniqueArrivalMessage(Some(number), Some(terminalName), Some(scheduled), Some(origin)) =>
        UniqueArrival(number, terminalName, scheduled, origin)
      case UniqueArrivalMessage(Some(number), Some(terminalName), Some(scheduled), None) =>
        LegacyUniqueArrival(number, terminalName, scheduled)
    }

  def arrivalsStateToSnapshotMessage(state: ArrivalsState): FlightStateSnapshotMessage = {
    val maybeStatusMessages: Option[FeedStatusesMessage] = state.maybeSourceStatuses.flatMap(feedStatuses => feedStatusesToMessage(feedStatuses.feedStatuses))

    FlightStateSnapshotMessage(
      state.arrivals.values.map(apiFlightToFlightMessage).toSeq,
      maybeStatusMessages
    )
  }

  def feedStatusesToMessage(statuses: FeedStatuses): Option[FeedStatusesMessage] = {
    val statusMessages = statuses.statuses.map(feedStatusToMessage)

    Option(FeedStatusesMessage(statusMessages, statuses.lastSuccessAt, statuses.lastFailureAt, statuses.lastUpdatesAt))
  }

  def feedStatusToMessage(feedStatus: FeedStatus): FeedStatusMessage = feedStatus match {
    case s: FeedStatusSuccess => FeedStatusMessage(Option(s.date), Option(s.updateCount), None)
    case s: FeedStatusFailure => FeedStatusMessage(Option(s.date), None, Option(s.message))
  }

  def restoreArrivalsFromSnapshot(restorer: ArrivalsRestorer[Arrival],
                                  snMessage: FlightStateSnapshotMessage): Unit = {
    restorer.applyUpdates(snMessage.flightMessages.map(flightMessageToApiFlight))
  }

  def feedStatusesFromSnapshotMessage(snMessage: FlightStateSnapshotMessage): Option[FeedStatuses] = {
    snMessage.statuses.map(feedStatusesFromFeedStatusesMessage)
  }

  def feedStatusesFromFeedStatusesMessage(message: FeedStatusesMessage): FeedStatuses = FeedStatuses(
    statuses = message.statuses.map(feedStatusFromFeedStatusMessage).toList,
    lastSuccessAt = message.lastSuccessAt,
    lastFailureAt = message.lastFailureAt,
    lastUpdatesAt = message.lastUpdatesAt
  )

  def feedStatusFromFeedStatusMessage(message: FeedStatusMessage): FeedStatus = {
    if (message.updates.isDefined)
      FeedStatusSuccess(message.date.getOrElse(0L), message.updates.getOrElse(0))
    else
      FeedStatusFailure(message.date.getOrElse(0L), message.message.getOrElse("n/a"))
  }

  def flightWithSplitsToMessage(f: ApiFlightWithSplits): FlightWithSplitsMessage = {
    FlightWithSplitsMessage(
      Option(FlightMessageConversion.apiFlightToFlightMessage(f.apiFlight)),
      f.splits.map(apiSplitsToMessage).toList,
      lastUpdated = f.lastUpdated)
  }

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage): ApiFlightWithSplits = ApiFlightWithSplits(
    FlightMessageConversion.flightMessageToApiFlight(fm.flight.get),
    fm.splits.map(sm => splitMessageToApiSplits(sm)).toSet,
    lastUpdated = fm.lastUpdated
  )

  def apiSplitsToMessage(s: Splits): SplitMessage = {
    SplitMessage(
      paxTypeAndQueueCount = s.splits.map(paxTypeAndQueueCountToMessage).toList,
      source = Option(s.source.toString),
      eventType = s.maybeEventType.map(_.toString),
      style = Option(s.splitStyle.name)
    )
  }

  def paxTypeAndQueueCountToMessage(ptqc: ApiPaxTypeAndQueueCount): PaxTypeAndQueueCountMessage = {
    PaxTypeAndQueueCountMessage(
      paxType = Option(ptqc.passengerType.name),
      queueType = Option(ptqc.queueType.toString),
      paxValue = Option(ptqc.paxCount),
      nationalities = Seq(),
      ages = Seq()
    )
  }

  def apiFlightToFlightMessage(apiFlight: Arrival): FlightMessage = {
    FlightMessage(
      operator = apiFlight.Operator.map(_.code),
      gate = apiFlight.Gate,
      stand = apiFlight.Stand,
      status = Option(apiFlight.Status.description),
      maxPax = apiFlight.MaxPax,
      actPax = apiFlight.ActPax,
      tranPax = apiFlight.TranPax,
      runwayID = apiFlight.RunwayID,
      baggageReclaimId = apiFlight.BaggageReclaimId,
      airportID = Option(apiFlight.AirportID.iata),
      terminal = Option(apiFlight.Terminal.toString),
      iCAO = Option(apiFlight.flightCodeString),
      iATA = Option(apiFlight.flightCodeString),
      origin = Option(apiFlight.Origin.toString),
      pcpTime = apiFlight.PcpTime,
      feedSources = apiFlight.FeedSources.map(_.toString).toSeq,
      scheduled = Option(apiFlight.Scheduled),
      estimated = apiFlight.Estimated,
      touchdown = apiFlight.Actual,
      estimatedChox = apiFlight.EstimatedChox,
      actualChox = apiFlight.ActualChox,
      carrierScheduled = apiFlight.CarrierScheduled,
      apiPax = apiFlight.ApiPax,
      redListPax = apiFlight.RedListPax,
      scheduledDeparture = apiFlight.ScheduledDeparture
    )
  }

  def flightMessageToApiFlight(flightMessage: FlightMessage): Arrival = {
    Arrival(
      Operator = flightMessage.operator.map(Operator),
      Status = ArrivalStatus(flightMessage.status.getOrElse("")),
      Estimated = flightMessage.estimated,
      Actual = flightMessage.touchdown,
      EstimatedChox = flightMessage.estimatedChox,
      ActualChox = flightMessage.actualChox,
      Gate = flightMessage.gate,
      Stand = flightMessage.stand,
      MaxPax = flightMessage.maxPax,
      ActPax = flightMessage.actPax,
      TranPax = flightMessage.tranPax,
      RunwayID = flightMessage.runwayID,
      BaggageReclaimId = flightMessage.baggageReclaimId,
      AirportID = PortCode(flightMessage.airportID.getOrElse("")),
      Terminal = Terminal(flightMessage.terminal.getOrElse("")),
      rawICAO = flightMessage.iCAO.getOrElse(""),
      rawIATA = flightMessage.iATA.getOrElse(""),
      Origin = PortCode(flightMessage.origin.getOrElse("")),
      PcpTime = flightMessage.pcpTime,
      Scheduled = flightMessage.scheduled.getOrElse(0L),
      FeedSources = flightMessage.feedSources.flatMap(FeedSource(_)).toSet,
      CarrierScheduled = flightMessage.carrierScheduled,
      ApiPax = flightMessage.apiPax,
      RedListPax = flightMessage.redListPax,
      ScheduledDeparture = flightMessage.scheduledDeparture,
    )
  }

  def flightsToMessage(flights: Iterable[ApiFlightWithSplits]): FlightsWithSplitsMessage =
    FlightsWithSplitsMessage(flights.map(FlightMessageConversion.flightWithSplitsToMessage).toSeq)
}
