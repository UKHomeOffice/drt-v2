package actors

import drt.shared._
import server.protobuf.messages.CrunchState.{FlightWithSplitsMessage, PaxTypeAndQueueCountMessage, SplitMessage}
import server.protobuf.messages.FlightsMessage.{FlightMessage, FlightStateSnapshotMessage}
import services.{ArrivalsState, SDate}

import scala.util.{Success, Try}

object FlightMessageConversion {

  def arrivalsStateFromSnapshotMessage(snMessage: FlightStateSnapshotMessage) = {
    ArrivalsState(snMessage.flightMessages.map(fm => {
      val arrival = FlightMessageConversion.flightMessageToApiFlight(fm)
      (arrival.uniqueId, arrival)
    }).toMap)
  }

  def flightWithSplitsToMessage(f: ApiFlightWithSplits): FlightWithSplitsMessage = {
    FlightWithSplitsMessage(
      Option(FlightMessageConversion.apiFlightToFlightMessage(f.apiFlight)),
      f.splits.map(apiSplitsToMessage).toList)
  }

  def apiSplitsToMessage(s: ApiSplits): SplitMessage = {
    SplitMessage(
      paxTypeAndQueueCount = s.splits.map(paxTypeAndQueueCountToMessage).toList,
      source = Option(s.source),
      eventType = s.eventType,
      style = Option(s.splitStyle.name)
    )
  }

  def paxTypeAndQueueCountToMessage(ptqc: ApiPaxTypeAndQueueCount) = {
    PaxTypeAndQueueCountMessage(
      Option(ptqc.passengerType.name),
      Option(ptqc.queueType),
      Option(ptqc.paxCount)
    )
  }

  def apiFlightToFlightMessage(apiFlight: Arrival): FlightMessage = {
    FlightMessage(
      operator = Some(apiFlight.Operator),
      gate = Some(apiFlight.Gate),
      stand = Some(apiFlight.Stand),
      status = Some(apiFlight.Status),
      maxPax = Some(apiFlight.MaxPax),
      actPax = Some(apiFlight.ActPax),
      tranPax = Some(apiFlight.TranPax),
      runwayID = Some(apiFlight.RunwayID),
      baggageReclaimId = Some(apiFlight.BaggageReclaimId),
      flightID = Some(apiFlight.FlightID),
      airportID = Some(apiFlight.AirportID),
      terminal = Some(apiFlight.Terminal),
      iCAO = Some(apiFlight.rawICAO),
      iATA = Some(apiFlight.rawIATA),
      origin = Some(apiFlight.Origin),
      pcpTime = Some(apiFlight.PcpTime),

      scheduled = Some(apiFlight.Scheduled),
      estimated = Some(apiFlight.Estimated),
      touchdown = Some(apiFlight.Actual),
      estimatedChox = Some(apiFlight.EstimatedChox),
      actualChox = Some(apiFlight.ActualChox)
    )
  }

  def millisOptionFromArrivalDateString(datetime: String): Option[Long] = datetime match {
    case "" => None
    case _ =>
      Try {
        SDate.parseString(datetime)
      } match {
        case Success(MilliDate(millis)) => Some(millis)
        case _ => None
      }
  }

  def flightMessageToApiFlight(flightMessage: FlightMessage): Arrival = {
    Arrival(
      Operator = flightMessage.operator.getOrElse(""),
      Status = flightMessage.status.getOrElse(""),
      Estimated = flightMessage.estimated.getOrElse(0),
      Actual = flightMessage.touchdown.getOrElse(0),
      EstimatedChox = flightMessage.estimatedChox.getOrElse(0),
      ActualChox = flightMessage.actualChox.getOrElse(0),
      Gate = flightMessage.gate.getOrElse(""),
      Stand = flightMessage.stand.getOrElse(""),
      MaxPax = flightMessage.maxPax.getOrElse(0),
      ActPax = flightMessage.actPax.getOrElse(0),
      TranPax = flightMessage.tranPax.getOrElse(0),
      RunwayID = flightMessage.runwayID.getOrElse(""),
      BaggageReclaimId = flightMessage.baggageReclaimId.getOrElse(""),
      FlightID = flightMessage.flightID.getOrElse(0),
      AirportID = flightMessage.airportID.getOrElse(""),
      Terminal = flightMessage.terminal.getOrElse(""),
      rawICAO = flightMessage.iCAO.getOrElse(""),
      rawIATA = flightMessage.iATA.getOrElse(""),
      Origin = flightMessage.origin.getOrElse(""),
      PcpTime = flightMessage.pcpTime.getOrElse(0),
      LastKnownPax = flightMessage.lastKnownPax,
      Scheduled = flightMessage.scheduled.getOrElse(0)
    )
  }

  def apiFlightDateTime(millisOption: Option[Long]): String = millisOption match {
    case Some(millis: Long) => SDate.jodaSDateToIsoString(SDate(millis))
    case _ => ""
  }
}
