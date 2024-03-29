package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

object ArrivalGenerator {
  def arrival(
               iata: String = "",
               icao: String = "",
               sch: MillisSinceEpoch = 0L,
               maxPax: Option[Int] = None,
               terminal: Terminal = Terminal("T1"),
               origin: PortCode = PortCode(""),
               operator: Option[Operator] = None,
               status: ArrivalStatus = ArrivalStatus(""),
               est: MillisSinceEpoch = 0L,
               predictions: Predictions = Predictions(0L, Map()),
               act: MillisSinceEpoch = 0L,
               estChox: MillisSinceEpoch = 0L,
               actChox: MillisSinceEpoch = 0L,
               gate: Option[String] = None,
               stand: Option[String] = None,
               runwayId: Option[String] = None,
               baggageReclaimId: Option[String] = None,
               airportId: PortCode = PortCode(""),
               feedSources: Set[FeedSource] = Set(),
               pcpTime: Option[MillisSinceEpoch] = None,
               passengerSources: Map[FeedSource, Passengers] = Map.empty
             ): Arrival =
    Arrival(
      Operator = operator,
      Status = status,
      Estimated = if (est != 0L) Some(est) else None,
      Predictions = predictions,
      Actual = if (act != 0L) Some(act) else None,
      EstimatedChox = if (estChox != 0L) Some(estChox) else None,
      ActualChox = if (actChox != 0L) Some(actChox) else None,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      Terminal = terminal,
      rawICAO = icao,
      rawIATA = iata,
      Origin = origin,
      PcpTime = if (pcpTime.isDefined) Option(pcpTime.get) else if (sch != 0L) Some(sch) else None,
      Scheduled = sch,
      FeedSources = feedSources,
      PassengerSources = passengerSources
    )

  def flightWithSplitsForDayAndTerminal(date: SDateLike, terminal: Terminal = T1): ApiFlightWithSplits = ApiFlightWithSplits(
    arrival(sch = date.millisSinceEpoch, terminal = terminal), Set(), Option(date.millisSinceEpoch)
  )

  def arrivalForDayAndTerminal(date: SDateLike, terminal: Terminal = T1): Arrival =
    arrival(sch = date.millisSinceEpoch, terminal = terminal)
}
