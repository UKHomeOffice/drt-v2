package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.time.SDateLike

trait BhxFlightsWithSplitsExportWithCombinedTerminals {
  val terminal: Terminal
  val start: SDateLike
  val end: SDateLike

  val terminalsToQuery: Seq[Terminal] = Seq(T1, T2)

  val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = (fws, _) => terminalsToQuery.contains(fws.apiFlight.Terminal)

  val requestForDiversions: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)
}

case class BhxFlightsWithSplitsWithoutActualApiExportWithCombinedTerminals(start: SDateLike, end: SDateLike, terminal: Terminal, paxFeedSourceOrder: List[FeedSource])
  extends FlightsWithSplitsWithoutActualApiExport with BhxFlightsWithSplitsExportWithCombinedTerminals

case class BhxFlightsWithSplitsWithActualApiExportWithCombinedTerminals(start: SDateLike, end: SDateLike, terminal: Terminal, paxFeedSourceOrder: List[FeedSource])
  extends FlightsWithSplitsWithActualApiExport with BhxFlightsWithSplitsExportWithCombinedTerminals
