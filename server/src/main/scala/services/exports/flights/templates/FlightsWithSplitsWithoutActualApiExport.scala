package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal

case class FlightsWithSplitsWithoutActualApiExport(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)
}
