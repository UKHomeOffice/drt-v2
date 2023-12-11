package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import services.exports.FlightExports.{actualAPISplitsForFlightInHeadingOrder, ageRangesFromSummary, nationalitiesFromSummary}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.time.SDateLike


trait FlightsWithSplitsWithActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)

  override val headings: String = ArrivalExportHeadings.arrivalWithSplitsAndRawApiHeadings

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = {
    val maybePaxSummary = maybeManifest.flatMap(PassengerInfo.manifestToFlightManifestSummary)

    (flightWithSplitsToCsvRow(fws) :::
      actualAPISplitsForFlightInHeadingOrder(fws, ArrivalExportHeadings.actualApiHeadings.split(",")).toList).map(s => s"$s") :::
      List(s""""${nationalitiesFromSummary(maybePaxSummary)}"""", s""""${ageRangesFromSummary(maybePaxSummary)}"""")
  }

}

case class FlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal, paxFeedSourceOrder: List[FeedSource]) extends FlightsWithSplitsWithActualApiExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
