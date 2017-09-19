package controllers

import drt.shared.Arrival
import org.joda.time.DateTimeZone
import services.SDate

object ArrivalGenerator {

  def apiFlight(
                 flightId: Int,
                 iata: String = "",
                 icao: String = "",
                 schDt: String = "",
                 actPax: Int = 0,
                 maxPax: Int = 0,
                 lastKnownPax: Option[Int] = None,
                 terminal: String = "T1",
                 origin: String = "",
                 operator: String = "",
                 status: String = "",
                 estDt: String = "",
                 actDt: String = "",
                 estChoxDt: String = "",
                 actChoxDt: String = "",
                 gate: String = "",
                 stand: String = "",
                 tranPax: Int = 0,
                 runwayId: String = "",
                 baggageReclaimId: String = "",
                 airportId: String = ""
               ): Arrival =
    Arrival(
      FlightID = flightId,
      rawICAO = icao,
      rawIATA = iata,
      SchDT = schDt,
      ActPax = actPax,

      Terminal = terminal,
      Origin = origin,
      Operator = operator,
      Status = status,
      EstDT = estDt,
      ActDT = actDt,
      EstChoxDT = estChoxDt,
      ActChoxDT = actChoxDt,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = if (schDt != "") SDate(schDt, DateTimeZone.UTC).millisSinceEpoch else 0,
      LastKnownPax = lastKnownPax,
      Scheduled = if (schDt != "") SDate(schDt, DateTimeZone.UTC).millisSinceEpoch else 0
    )
}
