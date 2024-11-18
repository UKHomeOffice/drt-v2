package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

trait BhxFlightsWithSplitsExportWithCombinedTerminals {
  val terminal: Terminal
  val start: LocalDate
  val end: LocalDate

  val terminalsToQuery: Seq[Terminal] = Seq(T1, T2)

  val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = (fws, _) => terminalsToQuery.contains(fws.apiFlight.Terminal)

  val requestForDiversions: FlightsRequest = GetFlightsForTerminals(SDate(start).millisSinceEpoch, SDate(end).millisSinceEpoch, terminalsToQuery)
}

case class BhxFlightsWithSplitsWithoutActualApiExportWithCombinedTerminals(start: LocalDate, end: LocalDate, terminal: Terminal, paxFeedSourceOrder: List[FeedSource])
  extends FlightsWithSplitsWithoutActualApiExport with BhxFlightsWithSplitsExportWithCombinedTerminals

case class BhxFlightsWithSplitsWithActualApiExportWithCombinedTerminals(start: LocalDate, end: LocalDate, terminal: Terminal, paxFeedSourceOrder: List[FeedSource])
  extends FlightsWithSplitsWithActualApiExport with BhxFlightsWithSplitsExportWithCombinedTerminals
