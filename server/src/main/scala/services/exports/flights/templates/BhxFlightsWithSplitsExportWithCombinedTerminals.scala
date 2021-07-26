package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import drt.shared.Terminals._
import drt.shared._

trait BhxFlightsWithSplitsExportWithCombinedTerminals {
  val terminal: Terminal
  val start: SDateLike
  val end: SDateLike

  val terminalsToQuery: Seq[Terminal] = Seq(T1, T2)

  val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = (fws, _) => terminalsToQuery.contains(fws.apiFlight.Terminal)

  val requestForDiversions: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)
}

case class BhxFlightsWithSplitsWithoutActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport with BhxFlightsWithSplitsExportWithCombinedTerminals

case class BhxFlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport with BhxFlightsWithSplitsExportWithCombinedTerminals
