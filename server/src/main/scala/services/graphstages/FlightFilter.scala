package services.graphstages

import drt.shared.{AirportConfig, ApiFlightWithSplits, LhrFlightDisplayFilter, LhrRedList, PortCode}
import drt.shared.Terminals.{T2, T5, Terminal}
import services.AirportToCountry
import services.exports.flights.templates.RedList

case class FlightFilter(filters: List[ApiFlightWithSplits => Boolean]) {
  def +(other: FlightFilter): FlightFilter = FlightFilter(filters ++ other.filters)

  def apply(fws: ApiFlightWithSplits): Boolean = filters.forall(_ (fws))
}

object FlightFilter {
  val lhrFlightFilter: LhrFlightDisplayFilter = LhrFlightDisplayFilter(
    RedList.ports.toList.contains, LhrRedList.t3RedListOpeningDate, LhrRedList.t4RedListOpeningDate, LhrRedList.t3NonRedListOpeningDate)

  def apply(filter: ApiFlightWithSplits => Boolean): FlightFilter = FlightFilter(List(filter))

  def validTerminalFilter(validTerminals: List[Terminal]): FlightFilter = FlightFilter(fws => validTerminals.contains(fws.apiFlight.Terminal))

  val notCancelledFilter: FlightFilter = FlightFilter(fws => !fws.apiFlight.isCancelled)

  val outsideCtaFilter: FlightFilter = FlightFilter(fws => !fws.apiFlight.Origin.isCta)

  val lhrRedListFilter: FlightFilter = FlightFilter { fws =>
    val isGreenOnlyTerminal = lhrFlightFilter.lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled).contains(fws.apiFlight.Terminal)
    val isRedListOrigin = AirportToCountry.isRedListed(fws.apiFlight.Origin)
    val okToProcess = !isRedListOrigin || !isGreenOnlyTerminal
    okToProcess
  }

  def regular(validTerminals: Iterable[Terminal]): FlightFilter =
    validTerminalFilter(validTerminals.toList) + notCancelledFilter + outsideCtaFilter

  def forPortConfig(config: AirportConfig): FlightFilter = config.portCode match {
    case PortCode("LHR") => regular(config.terminals) + lhrRedListFilter
    case _ => regular(config.terminals)
  }
}
