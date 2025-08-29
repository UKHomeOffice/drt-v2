package services.graphstages

import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.services.AirportInfoService
import uk.gov.homeoffice.drt.time.SDate

case class FlightFilter(filters: List[(ApiFlightWithSplits, RedListUpdates) => Boolean]) {
  def +(other: FlightFilter): FlightFilter = FlightFilter(filters ++ other.filters)

  def apply(fws: ApiFlightWithSplits, redListUpdates: RedListUpdates): Boolean = filters.forall(_(fws, redListUpdates))
}

object FlightFilter {
  private val terminalTypes: LhrTerminalTypes = LhrTerminalTypes(LhrRedListDatesImpl)

  def apply(filter: (ApiFlightWithSplits, RedListUpdates) => Boolean): FlightFilter = FlightFilter(List(filter))

  def validTerminalFilter(validTerminals: List[Terminal]): FlightFilter = FlightFilter((fws, _) => validTerminals.contains(fws.apiFlight.Terminal))

  val notDivertedFilter: FlightFilter = FlightFilter((fws, _) => !fws.apiFlight.isDiverted)

  val notCancelledFilter: FlightFilter = FlightFilter((fws, _) => !fws.apiFlight.isCancelled)

  val outsideCtaFilter: FlightFilter = FlightFilter((fws, _) => !fws.apiFlight.Origin.isCta)

  val lhrRedListFilter: FlightFilter = FlightFilter { (fws, redListUpdates) =>
    val isGreenOnlyTerminal = terminalTypes.lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled).contains(fws.apiFlight.Terminal)
    val isRedListOrigin = AirportInfoService.isRedListed(fws.apiFlight.Origin, fws.apiFlight.Scheduled, redListUpdates)
    val okToProcess = !isRedListOrigin || !isGreenOnlyTerminal
    okToProcess
  }

  def regular(validTerminals: Iterable[Terminal]): FlightFilter =
    validTerminalFilter(validTerminals.toList) + notDivertedFilter + notCancelledFilter + outsideCtaFilter

  def forPortConfig(config: AirportConfig): FlightFilter = {
    val terminals = regular(config.terminalsForDate(SDate.now().toLocalDate))
    config.portCode match {
      case PortCode("LHR") => terminals + lhrRedListFilter
      case _ => terminals
    }
  }
}
