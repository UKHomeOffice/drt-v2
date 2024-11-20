package services.exports.flights.templates

import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import services.exports.FlightExports.{actualAPISplitsForFlightInHeadingOrder, ageRangesFromSummary, nationalitiesFromSummary}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.time.LocalDate


trait FlightsWithSplitsWithActualApiExport extends FlightsWithSplitsExport {

  override val headings: String = ArrivalExportHeadings.arrivalWithSplitsAndRawApiHeadings

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = {
    val maybePaxSummary = maybeManifest.flatMap(PassengerInfo.manifestToFlightManifestSummary)

    (flightWithSplitsToCsvRow(fws) :::
      actualAPISplitsForFlightInHeadingOrder(fws, ArrivalExportHeadings.actualApiHeadings.split(",")).toList).map(s => s"$s") :::
      List(s""""${nationalitiesFromSummary(maybePaxSummary)}"""", s""""${ageRangesFromSummary(maybePaxSummary)}"""")
  }

}

case class FlightsWithSplitsWithActualApiExportImpl(start: LocalDate,
                                                    end: LocalDate,
                                                    terminals: Seq[Terminal],
                                                    paxFeedSourceOrder: List[FeedSource],
                                                   ) extends FlightsWithSplitsWithActualApiExport {
  override val flightsFilter: (ApiFlightWithSplits, Seq[Terminal]) => Boolean = standardFilter
}
