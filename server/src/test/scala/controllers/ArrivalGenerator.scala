package controllers

import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, ArrivalStatus, Operator, Prediction, Predictions, TotalPaxSource}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

object ArrivalGenerator {
  def arrival(iata: String = "",
              icao: String = "",
              schDt: String = "",
              actPax: Option[Int] = None,
              maxPax: Option[Int] = None,
              terminal: Terminal = T1,
              origin: PortCode = PortCode("JFK"),
              operator: Option[Operator] = None,
              status: ArrivalStatus = ArrivalStatus(""),
              estDt: String = "",
              predictions: Predictions = Predictions(0L, Map()),
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
              feedSources: Set[FeedSource] = Set(),
              apiPax: Option[Int] = None,
              totalPax: Map[FeedSource, Option[Int]] = Map.empty
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
      Estimated = if (estDt.nonEmpty) Option(SDate.parseString(estDt).millisSinceEpoch) else None,
      Predictions = predictions,
      Actual = if (actDt.nonEmpty) Option(SDate.parseString(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (estChoxDt.nonEmpty) Option(SDate.parseString(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (actChoxDt.nonEmpty) Option(SDate.parseString(actChoxDt).millisSinceEpoch) else None,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = pcpTime,
      Scheduled = if (schDt.nonEmpty) SDate(schDt).millisSinceEpoch else 0,
      FeedSources = feedSources,
      ApiPax = apiPax,
      TotalPax = totalPax
    )
  }

  def flightWithSplitsForDayAndTerminal(date: SDateLike, terminal: Terminal = T1): ApiFlightWithSplits = ApiFlightWithSplits(
    ArrivalGenerator.arrival(schDt = date.toISOString, terminal = terminal), Set(), Option(date.millisSinceEpoch)
  )

  def arrivalForDayAndTerminal(date: SDateLike, terminal: Terminal = T1): Arrival =
    ArrivalGenerator.arrival(schDt = date.toISOString, terminal = terminal)
}
