package controllers

import drt.shared.Arrival
import org.joda.time.DateTimeZone
import org.springframework.util.StringUtils
import services.SDate

object ArrivalGenerator {

  def apiFlight(
                 flightId: Option[Int] = None,
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
      FlightID = flightId,
      rawICAO = icao,
      rawIATA = iata,
      ActPax = actPax,
      Terminal = terminal,
      Origin = origin,
      Operator = operator,
      Status = status,
      Estimated = if (!StringUtils.isEmpty(estDt)) Some(SDate.parseString(estDt).millisSinceEpoch) else None,
      Actual = if (!StringUtils.isEmpty(actDt)) Some(SDate.parseString(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (!StringUtils.isEmpty(estChoxDt)) Some(SDate.parseString(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (!StringUtils.isEmpty(actChoxDt)) Some(SDate.parseString(actChoxDt).millisSinceEpoch) else None,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = if (!StringUtils.isEmpty(schDt)) Some(SDate(schDt, DateTimeZone.UTC).millisSinceEpoch) else None,
      LastKnownPax = lastKnownPax,
      Scheduled = if (!StringUtils.isEmpty(schDt)) SDate(schDt, DateTimeZone.UTC).millisSinceEpoch else 0
    )
}
