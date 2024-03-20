package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

object ArrivalGenerator {
  def arrival(iata: String = "",
              sch: MillisSinceEpoch = 0L,
              maxPax: Option[Int] = None,
              terminal: Terminal = Terminal("T1"),
              origin: PortCode = PortCode(""),
              operator: Option[Operator] = None,
              status: ArrivalStatus = ArrivalStatus(""),
              est: MillisSinceEpoch = 0L,
              act: MillisSinceEpoch = 0L,
              estChox: MillisSinceEpoch = 0L,
              actChox: MillisSinceEpoch = 0L,
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
      scheduled = SDate(sch).millisSinceEpoch,
      estimated = if (est != 0L) Option(est) else None,
      touchdown = if (act != 0L) Option(act) else None,
      estimatedChox = if (estChox != 0L) Option(estChox) else None,
      actualChox = if (actChox != 0L) Option(actChox) else None,
      status = status.description,
      gate = gate,
      stand = stand,
      runway = runwayId,
      baggageReclaim = baggageReclaimId,
    )
  }

  def flightWithSplitsForDayAndTerminal(date: SDateLike, terminal: Terminal = T1, feedSource: FeedSource): ApiFlightWithSplits = ApiFlightWithSplits(
    arrival(sch = date.millisSinceEpoch, terminal = terminal).toArrival(feedSource), Set(), Option(date.millisSinceEpoch)
  )

  def arrivalForDayAndTerminal(date: SDateLike, terminal: Terminal = T1): LiveArrival =
    arrival(sch = date.millisSinceEpoch, terminal = terminal)
}
