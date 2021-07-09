package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import drt.shared.Terminals.Terminal
import drt.shared.{ApiFlightWithSplits, SDateLike}

trait FlightsWithSplitsWithoutActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)
}

case class FlightsWithSplitsWithoutActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport {
  override val flightsFilter: (Terminal, ApiFlightWithSplits) => Boolean = standardFilter
}
