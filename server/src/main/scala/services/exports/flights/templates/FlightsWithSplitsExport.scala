package services.exports.flights.templates

import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import services.exports.FlightExports
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}


trait FlightsWithSplitsExport extends FlightsExport {
  protected def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    FlightExports.flightWithSplitsToCsvFields(paxFeedSourceOrder)(fws.apiFlight) ++
      List(pcpPax(fws), apiIsInvalid(fws)) ++
      splitsForSources(fws)
  }

  def apiIsInvalid(fws: ApiFlightWithSplits): String =
    if (fws.hasApi && !fws.hasValidApi) "Y" else ""

  def pcpPax(fws: ApiFlightWithSplits): String =
    if (fws.apiFlight.Origin.isDomesticOrCta) "-"
    else fws.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).map(_.toString).getOrElse("0")

  override val headings: String = ArrivalExportHeadings.arrivalWithSplitsHeadings

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = flightWithSplitsToCsvRow(fws)
}
