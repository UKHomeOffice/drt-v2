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
      FlightID = Some(flightId),
      rawICAO = icao,
      rawIATA = iata,
      ActPax = if (actPax==0) None else Some(actPax),
      Terminal = terminal,
      Origin = origin,
      Operator = Some(operator),
      Status = status,
      Estimated = if (!StringUtils.isEmpty(estDt)) Some(SDate.parseString(estDt).millisSinceEpoch) else None,
      Actual = if (!StringUtils.isEmpty(actDt)) Some(SDate.parseString(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (!StringUtils.isEmpty(estChoxDt)) Some(SDate.parseString(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (!StringUtils.isEmpty(actChoxDt)) Some(SDate.parseString(actChoxDt).millisSinceEpoch) else None,
      Gate = if (gate.isEmpty) None else Some(gate),
      Stand = if (stand.isEmpty) None else Some(stand),
      MaxPax = if (maxPax == 0) None else Some(maxPax),
      TranPax = if (tranPax == 0) None else Some(tranPax),
      RunwayID = if (runwayId.isEmpty) None else Some(runwayId),
      BaggageReclaimId = if (baggageReclaimId.isEmpty) None else Some(baggageReclaimId),
      AirportID = airportId,
      PcpTime = if (!StringUtils.isEmpty(schDt)) Some(SDate(schDt, DateTimeZone.UTC).millisSinceEpoch) else None,
      LastKnownPax = lastKnownPax,
      Scheduled = if (!StringUtils.isEmpty(schDt)) SDate(schDt, DateTimeZone.UTC).millisSinceEpoch else 0
    )
}
