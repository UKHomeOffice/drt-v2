package services.exports.flights.templates

import services.exports.FlightExports
import services.exports.FlightExports.{apiIsInvalid, splitsForSources}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.models.VoyageManifest


trait FlightsWithSplitsExport extends FlightsExport {
  protected def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] =
    FlightExports.flightWithSplitsToCsvFields(paxFeedSourceOrder)(fws.apiFlight) ++
      List(apiIsInvalid(fws)) ++
      splitsForSources(fws, paxFeedSourceOrder)

  override val headings: String = ArrivalExportHeadings.arrivalWithSplitsHeadings

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = flightWithSplitsToCsvRow(fws)
}
