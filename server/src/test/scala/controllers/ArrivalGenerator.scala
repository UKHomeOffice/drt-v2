package controllers

import drt.shared.Arrival
import org.joda.time.DateTimeZone
import org.springframework.util.StringUtils
import services.SDate

object ArrivalGenerator {

  def apiFlight(
                 flightId: Int = 0,
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
      Estimated = if (!StringUtils.isEmpty(estDt)) SDate.parseString(estDt).millisSinceEpoch else 0,
      ActDT = actDt,
      Actual = if (!StringUtils.isEmpty(actDt)) SDate.parseString(actDt).millisSinceEpoch else 0,
      EstChoxDT = estChoxDt,
      EstimatedChox = if (!StringUtils.isEmpty(estChoxDt)) SDate.parseString(estChoxDt).millisSinceEpoch else 0,
      ActChoxDT = actChoxDt,
      ActualChox = if (!StringUtils.isEmpty(actChoxDt)) SDate.parseString(actChoxDt).millisSinceEpoch else 0,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = if (!StringUtils.isEmpty(schDt)) SDate(schDt, DateTimeZone.UTC).millisSinceEpoch else 0,
      LastKnownPax = lastKnownPax,
      Scheduled = if (!StringUtils.isEmpty(schDt)) SDate(schDt, DateTimeZone.UTC).millisSinceEpoch else 0
    )
}
