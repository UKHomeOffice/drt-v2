package controllers

import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{Arrival, FeedSource, PortCode}
import org.springframework.util.StringUtils
import services.SDate

object ArrivalGenerator {

  def arrival(iata: String = "",
              icao: String = "",
              schDt: String = "",
              actPax: Option[Int] = None,
              maxPax: Option[Int] = None,
              terminal: Terminal = T1,
              origin: PortCode = PortCode(""),
              operator: Option[String] = None,
              status: String = "",
              estDt: String = "",
              actDt: String = "",
              estChoxDt: String = "",
              actChoxDt: String = "",
              pcpDt: String = "",
              gate: Option[String] = None,
              stand: Option[String] = None,
              tranPax: Option[Int] = None,
              runwayId: Option[String] = None,
              baggageReclaimId: Option[String] = None,
              airportId: PortCode = PortCode(""),
              feedSources: Set[FeedSource] = Set()
             ): Arrival = {
    val pcpTime = if (pcpDt.nonEmpty) Option(SDate(pcpDt).millisSinceEpoch) else if (schDt.nonEmpty) Option(SDate(schDt).millisSinceEpoch) else None

    Arrival(
      rawICAO = icao,
      rawIATA = iata,
      ActPax = actPax,
      Terminal = terminal,
      Origin = origin,
      Operator = operator,
      Status = status,
      Estimated = if (!StringUtils.isEmpty(estDt)) Option(SDate.parseString(estDt).millisSinceEpoch) else None,
      Actual = if (!StringUtils.isEmpty(actDt)) Option(SDate.parseString(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (!StringUtils.isEmpty(estChoxDt)) Option(SDate.parseString(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (!StringUtils.isEmpty(actChoxDt)) Option(SDate.parseString(actChoxDt).millisSinceEpoch) else None,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = pcpTime,
      Scheduled = if (!StringUtils.isEmpty(schDt)) SDate(schDt).millisSinceEpoch else 0,
      FeedSources = feedSources
    )
  }
}
