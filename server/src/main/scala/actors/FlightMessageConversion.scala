package actors

import drt.shared.{Arrival, MilliDate}
import server.protobuf.messages.FlightsMessage.FlightMessage
import services.SDate

import scala.util.{Success, Try}

object FlightMessageConversion {
  def apiFlightToFlightMessage(apiFlight: Arrival): FlightMessage = {
    val createdAt = SDate.now().millisSinceEpoch

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

      scheduled = millisOptionFromArrivalDateString(apiFlight.SchDT),
      estimated = millisOptionFromArrivalDateString(apiFlight.EstDT),
      touchdown = millisOptionFromArrivalDateString(apiFlight.ActDT),
      estimatedChox = millisOptionFromArrivalDateString(apiFlight.EstChoxDT),
      actualChox = millisOptionFromArrivalDateString(apiFlight.ActChoxDT),

      createdAt = Option(createdAt)
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
    flightMessage.schDTOLD match {
      case Some(_) => flightMessageV1ToArrival(flightMessage)
      case None => flightMessageV2ToArrival(flightMessage)
    }
  }

  def flightMessageV2ToArrival(flightMessage: FlightMessage): Arrival = {
    Arrival(
      Operator = flightMessage.operator.getOrElse(""),
      Status = flightMessage.status.getOrElse(""),
      EstDT = apiFlightDateTime(flightMessage.estimated),
      ActDT = apiFlightDateTime(flightMessage.touchdown),
      EstChoxDT = apiFlightDateTime(flightMessage.estimatedChox),
      ActChoxDT = apiFlightDateTime(flightMessage.actualChox),
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
      SchDT = apiFlightDateTime(flightMessage.scheduled),
      PcpTime = flightMessage.pcpTime.getOrElse(0),
      LastKnownPax = flightMessage.lastKnownPax
    )
  }

  def flightMessageV1ToArrival(flightMessage: FlightMessage): Arrival = {
    Arrival(
      Operator = flightMessage.operator.getOrElse(""),
      Status = flightMessage.status.getOrElse(""),
      EstDT = flightMessage.estDTOLD.getOrElse(""),
      ActDT = flightMessage.actDTOLD.getOrElse(""),
      EstChoxDT = flightMessage.estChoxDTOLD.getOrElse(""),
      ActChoxDT = flightMessage.actChoxDTOLD.getOrElse(""),
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
      SchDT = flightMessage.schDTOLD.getOrElse(""),
      PcpTime = flightMessage.pcpTime.getOrElse(0)
    )
  }

  def apiFlightDateTime(millisOption: Option[Long]): String = millisOption match {
    case Some(millis: Long) => SDate.jodaSDateToIsoString(SDate(millis))
    case _ => ""
  }
}
