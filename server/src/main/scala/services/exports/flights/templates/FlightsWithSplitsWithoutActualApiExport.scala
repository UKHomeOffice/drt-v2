package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

trait FlightsWithSplitsWithoutActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)
}

case class FlightsWithSplitsWithoutActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
