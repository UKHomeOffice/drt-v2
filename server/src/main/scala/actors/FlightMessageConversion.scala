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
      operator = apiFlight.Operator,
      gate = apiFlight.Gate,
      stand = apiFlight.Stand,
      status = Some(apiFlight.Status),
      maxPax = apiFlight.MaxPax,
      actPax = apiFlight.ActPax,
      tranPax = apiFlight.TranPax,
      runwayID = apiFlight.RunwayID,
      baggageReclaimId = apiFlight.BaggageReclaimId,
      flightID = apiFlight.FlightID,
      airportID = Some(apiFlight.AirportID),
      terminal = Some(apiFlight.Terminal),
      iCAO = Some(apiFlight.rawICAO),
      iATA = Some(apiFlight.rawIATA),
      origin = Some(apiFlight.Origin),
      pcpTime = apiFlight.PcpTime,

      scheduled = Some(apiFlight.Scheduled),
      estimated = apiFlight.Estimated,
      touchdown = apiFlight.Actual,
      estimatedChox = apiFlight.EstimatedChox,
      actualChox = apiFlight.ActualChox
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
      Operator = flightMessage.operator.filter(_ != ""),
      Status = flightMessage.status.getOrElse(""),
      Estimated = flightMessage.estimated.filter(_!= 0),
      Actual = flightMessage.touchdown.filter(_!=0),
      EstimatedChox = flightMessage.estimatedChox.filterNot(_!=0),
      ActualChox = flightMessage.actualChox.filter(_!=0),
      Gate = flightMessage.gate.filter(_ != ""),
      Stand = flightMessage.stand.filter(_ != ""),
      MaxPax = flightMessage.maxPax.filter(_ != 0),
      ActPax = flightMessage.actPax.filter(_ != 0),
      TranPax = flightMessage.tranPax,
      RunwayID = flightMessage.runwayID.filter(_ != ""),
      BaggageReclaimId = flightMessage.baggageReclaimId.filter(_ != ""),
      FlightID = flightMessage.flightID.filter(_ != ""),
      AirportID = flightMessage.airportID.getOrElse(""),
      Terminal = flightMessage.terminal.getOrElse(""),
      rawICAO = flightMessage.iCAO.getOrElse(""),
      rawIATA = flightMessage.iATA.getOrElse(""),
      Origin = flightMessage.origin.getOrElse(""),
      PcpTime = flightMessage.pcpTime.filter(_ != 0),
      LastKnownPax = flightMessage.lastKnownPax,
      Scheduled = flightMessage.scheduled.getOrElse(0L)
    )
  }

  def apiFlightDateTime(millisOption: Option[Long]): String = millisOption match {
    case Some(millis: Long) => SDate.jodaSDateToIsoString(SDate(millis))
    case _ => ""
  }
}
