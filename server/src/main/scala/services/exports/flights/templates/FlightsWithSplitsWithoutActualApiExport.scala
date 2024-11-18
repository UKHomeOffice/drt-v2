package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

trait FlightsWithSplitsWithoutActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(SDate(start).millisSinceEpoch, SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch, terminal)
}

case class FlightsWithSplitsWithoutActualApiExportImpl(start: LocalDate,
                                                       end: LocalDate,
                                                       terminal: Terminal,
                                                       paxFeedSourceOrder: List[FeedSource],
                                                      ) extends FlightsWithSplitsWithoutActualApiExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
