package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, Operator, Prediction}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}


object ArrivalGenerator {
  def apiFlight(
                 iata: String = "",
                 icao: String = "",
                 schDt: String = "",
                 actPax: Option[Int] = None,
                 maxPax: Option[Int] = None,
                 terminal: Terminal = Terminal("T1"),
                 origin: PortCode = PortCode(""),
                 operator: Option[Operator] = None,
                 status: ArrivalStatus = ArrivalStatus(""),
                 estDt: String = "",
                 predTouchdownDt: String = "",
                 actDt: String = "",
                 estChoxDt: String = "",
                 actChoxDt: String = "",
                 gate: Option[String] = None,
                 stand: Option[String] = None,
                 tranPax: Option[Int] = None,
                 runwayId: Option[String] = None,
                 baggageReclaimId: Option[String] = None,
                 airportId: PortCode = PortCode(""),
                 feedSources: Set[FeedSource] = Set(),
                 pcpTime: Option[MillisSinceEpoch] = None
               ): Arrival =
    Arrival(
      Operator = operator,
      Status = status,
      Estimated = if (estDt != "") Option(SDate(estDt).millisSinceEpoch) else None,
      PredictedTouchdown = if (predTouchdownDt != "") Option(Prediction(SDate.now().millisSinceEpoch, SDate(predTouchdownDt).millisSinceEpoch)) else None,
      Actual = if (actDt != "") Option(SDate(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (estChoxDt != "") Option(SDate(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (actChoxDt != "") Option(SDate(actChoxDt).millisSinceEpoch) else None,
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
      PcpTime = if (pcpTime.isDefined) Option(pcpTime.get) else if (schDt != "") Some(SDate(schDt).millisSinceEpoch) else None,
      Scheduled = if (schDt != "") SDate(schDt).millisSinceEpoch else 0L,
      FeedSources = feedSources
    )
}
