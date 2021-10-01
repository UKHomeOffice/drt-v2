package services.graphstages

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import drt.shared.{AirportConfig, ApiFlightWithSplits, PortCode}
import services.AirportToCountry
import uk.gov.homeoffice.drt.redlist.RedListUpdates

case class FlightFilter(filters: List[(ApiFlightWithSplits, RedListUpdates) => Boolean]) {
  def +(other: FlightFilter): FlightFilter = FlightFilter(filters ++ other.filters)

  def apply(fws: ApiFlightWithSplits, redListUpdates: RedListUpdates): Boolean = filters.forall(_(fws, redListUpdates))
}

object FlightFilter {
  private val terminalTypes: LhrTerminalTypes = LhrTerminalTypes(LhrRedListDatesImpl)

  def apply(filter: (ApiFlightWithSplits, RedListUpdates) => Boolean): FlightFilter = FlightFilter(List(filter))

  def validTerminalFilter(validTerminals: List[Terminal]): FlightFilter = FlightFilter((fws, _) => validTerminals.contains(fws.apiFlight.Terminal))

  val notCancelledFilter: FlightFilter = FlightFilter((fws, _) => !fws.apiFlight.isCancelled)

  val outsideCtaFilter: FlightFilter = FlightFilter((fws, _) => !fws.apiFlight.Origin.isCta)

  val lhrRedListFilter: FlightFilter = FlightFilter { (fws, redListUpdates) =>
    val isGreenOnlyTerminal = terminalTypes.lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled).contains(fws.apiFlight.Terminal)
    val isRedListOrigin = AirportToCountry.isRedListed(fws.apiFlight.Origin, fws.apiFlight.Scheduled, redListUpdates)
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
