package services.graphstages

import drt.shared.Terminals.Terminal
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import drt.shared.{AirportConfig, ApiFlightWithSplits, PortCode}
import services.AirportToCountry

case class FlightFilter(filters: List[ApiFlightWithSplits => Boolean]) {
  def +(other: FlightFilter): FlightFilter = FlightFilter(filters ++ other.filters)

  def apply(fws: ApiFlightWithSplits): Boolean = filters.forall(_ (fws))
}

object FlightFilter {
  private val terminalTypes: LhrTerminalTypes = LhrTerminalTypes(LhrRedListDatesImpl)

  def apply(filter: ApiFlightWithSplits => Boolean): FlightFilter = FlightFilter(List(filter))

  def validTerminalFilter(validTerminals: List[Terminal]): FlightFilter = FlightFilter(fws => validTerminals.contains(fws.apiFlight.Terminal))

  val notCancelledFilter: FlightFilter = FlightFilter(fws => !fws.apiFlight.isCancelled)

  val outsideCtaFilter: FlightFilter = FlightFilter(fws => !fws.apiFlight.Origin.isCta)

  val lhrRedListFilter: FlightFilter = FlightFilter { fws =>
    val isGreenOnlyTerminal = terminalTypes.lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled).contains(fws.apiFlight.Terminal)
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
