package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.{ApiFeedSource, Arrival}



object ArrivalGenerator {

  def apiFlight(
                 iata: String = "",
                 icao: String = "",
                 schDt: String = "",
                 actPax: Option[Int] = None,
                 maxPax: Option[Int] = None,
                 lastKnownPax: Option[Int] = None,
                 terminal: String = "T1",
                 origin: String = "",
                 operator: Option[String] = None,
                 status: String = "",
                 estDt: String = "",
                 actDt: String = "",
                 estChoxDt: String = "",
                 actChoxDt: String = "",
                 gate: Option[String] = None,
                 stand: Option[String] = None,
                 tranPax: Option[Int] = None,
                 runwayId: Option[String] = None,
                 baggageReclaimId: Option[String] = None,
                 airportId: String = ""
               ): Arrival =
    Arrival(
      Operator = operator,
      Status = status,
      Estimated = if (estDt != "") Some(SDate(estDt).millisSinceEpoch) else None,
      Actual = if (actDt != "") Some(SDate(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (estChoxDt != "") Some(SDate(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (actChoxDt != "") Some(SDate(actChoxDt).millisSinceEpoch) else None,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      ActPax = actPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      Terminal = terminal,
      rawICAO = icao,
      rawIATA = iata,
      Origin = origin,
      PcpTime = if (schDt != "") Some(SDate(schDt).millisSinceEpoch) else None,
      Scheduled = if (schDt != "") SDate(schDt).millisSinceEpoch else 0L,
      FeedSources = Set(ApiFeedSource)
    )
}
