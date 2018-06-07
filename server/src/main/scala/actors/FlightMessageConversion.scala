package actors

import drt.shared._
import org.apache.commons.lang3.StringUtils
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
      operator = apiFlight.Operator.filter(StringUtils.isNotBlank(_)),
      gate = apiFlight.Gate.filter(StringUtils.isNotBlank(_)),
      stand = apiFlight.Stand.filter(StringUtils.isNotBlank(_)),
      status = Option(StringUtils.trimToNull(apiFlight.Status)),
      maxPax = apiFlight.MaxPax.filter(_ != 0),
      actPax = apiFlight.ActPax.filter(_ != 0),
      tranPax = apiFlight.TranPax,
      runwayID = apiFlight.RunwayID.filter(StringUtils.isNotBlank(_)),
      baggageReclaimId = apiFlight.BaggageReclaimId.filter(StringUtils.isNotBlank(_)),
      flightID = apiFlight.FlightID.filter(_ != 0),
      airportID = Option(StringUtils.trimToNull(apiFlight.AirportID)),
      terminal = Option(StringUtils.trimToNull(apiFlight.Terminal)),
      iCAO = Option(StringUtils.trimToNull(apiFlight.rawICAO)),
      iATA = Option(StringUtils.trimToNull(apiFlight.rawIATA)),
      origin = Option(StringUtils.trimToNull(apiFlight.Origin)),
      pcpTime = apiFlight.PcpTime.filter(_ != 0),

      scheduled = Option(apiFlight.Scheduled).filter(_ != 0),
      estimated = apiFlight.Estimated.filter(_ != 0),
      touchdown = apiFlight.Actual.filter(_ != 0),
      estimatedChox = apiFlight.EstimatedChox.filter(_ != 0),
      actualChox = apiFlight.ActualChox.filter(_ != 0)
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
      Operator = flightMessage.operator.filter(StringUtils.isNotBlank(_)),
      Status = flightMessage.status.getOrElse(""),
      Estimated = flightMessage.estimated.filter(_ != 0),
      Actual = flightMessage.touchdown.filter(_ != 0),
      EstimatedChox = flightMessage.estimatedChox.filterNot(_ != 0),
      ActualChox = flightMessage.actualChox.filter(_ != 0),
      Gate = flightMessage.gate.filter(StringUtils.isNotBlank(_)),
      Stand = flightMessage.stand.filter(StringUtils.isNotBlank(_)),
      MaxPax = flightMessage.maxPax.filter(_ != 0),
      ActPax = flightMessage.actPax.filter(_ != 0),
      TranPax = flightMessage.tranPax,
      RunwayID = flightMessage.runwayID.filter(StringUtils.isNotBlank(_)),
      BaggageReclaimId = flightMessage.baggageReclaimId.filter(StringUtils.isNotBlank(_)),
      FlightID = flightMessage.flightID.filter(StringUtils.isNotBlank(_)),
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
