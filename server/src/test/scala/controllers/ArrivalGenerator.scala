package controllers

import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, ArrivalStatus, FlightCode, LiveArrival, Operator, Passengers, Prediction, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

object ArrivalGenerator {
  def arrival(iata: String = "",
              schDt: String = "",
              maxPax: Option[Int] = None,
              terminal: Terminal = T1,
              origin: PortCode = PortCode("JFK"),
              operator: Option[Operator] = None,
              status: ArrivalStatus = ArrivalStatus(""),
              estDt: String = "",
              actDt: String = "",
              estChoxDt: String = "",
              actChoxDt: String = "",
              gate: Option[String] = None,
              stand: Option[String] = None,
              runwayId: Option[String] = None,
              baggageReclaimId: Option[String] = None,
              totalPax: Option[Int] = None,
              transPax: Option[Int] = None,
             ): LiveArrival = {
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(iata)

    LiveArrival(
      operator = operator.map(_.code),
      maxPax = maxPax,
      totalPax = totalPax,
      transPax = transPax,
      terminal = terminal,
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = suffix.map(_.suffix),
      origin = origin.iata,
      scheduled = if (schDt.nonEmpty) SDate(schDt).millisSinceEpoch else 0,
      estimated = if (estDt.nonEmpty) Option(SDate.parseString(estDt).millisSinceEpoch) else None,
      touchdown = if (actDt.nonEmpty) Option(SDate.parseString(actDt).millisSinceEpoch) else None,
      estimatedChox = if (estChoxDt.nonEmpty) Option(SDate.parseString(estChoxDt).millisSinceEpoch) else None,
      actualChox = if (actChoxDt.nonEmpty) Option(SDate.parseString(actChoxDt).millisSinceEpoch) else None,
      status = status.description,
      gate = gate,
      stand = stand,
      runway = runwayId,
      baggageReclaim = baggageReclaimId,
    )
  }

  def flightWithSplitsForDayAndTerminal(date: SDateLike, terminal: Terminal = T1, feedSource: FeedSource): ApiFlightWithSplits = ApiFlightWithSplits(
    ArrivalGenerator.arrival(schDt = date.toISOString, terminal = terminal).toArrival(feedSource), Set(), Option(date.millisSinceEpoch)
  )

  def arrivalForDayAndTerminal(date: SDateLike, terminal: Terminal = T1): LiveArrival =
    ArrivalGenerator.arrival(schDt = date.toISOString, terminal = terminal)
}
