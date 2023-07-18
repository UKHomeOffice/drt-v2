package services.exports.flights.templates

import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports.{FeedSource, MlFeedSource}
import uk.gov.homeoffice.drt.time.SDateLike


trait AdminExport extends FlightsWithSplitsWithActualApiExport {
  override val headings: String = ArrivalExportHeadings.arrivalWithSplitsAndRawApiHeadings.replace("PCP Pax", "Predicted PCP Pax")

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = {
    val maybePaxSummary = maybeManifest.flatMap(PassengerInfo.manifestToFlightManifestSummary)

    (flightWithSplitsToCsvRow(fws) :::
      actualAPISplitsForFlightInHeadingOrder(fws, ArrivalExportHeadings.actualApiHeadings.split(",")).toList).map(s => s"$s") :::
      List(s""""${nationalitiesFromSummary(maybePaxSummary)}"""", s""""${ageRangesFromSummary(maybePaxSummary)}"""")
  }

  override def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    val apiIsInvalid = if (fws.hasApi && !fws.hasValidApi) "Y" else ""
    val pcpPax =
      if (fws.apiFlight.Origin.isDomesticOrCta) "-"
      else fws.bestPaxSource(paxFeedSourceOrder).getPcpPax.map(_.toString).getOrElse("0")
    val predPcpPax =
      if (fws.apiFlight.Origin.isDomesticOrCta) "-"
      else fws.apiFlight.PassengerSources.get(MlFeedSource).flatMap(p => p.getPcpPax.map(_.toString)).getOrElse("-")
    flightWithSplitsToCsvFields(fws, millisToLocalDateTimeStringFn) ++
      List(pcpPax, predPcpPax, apiIsInvalid) ++
      splitsForSources(fws)
  }
}

case class AdminExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal, paxFeedSourceOrder: List[FeedSource]) extends FlightsWithSplitsWithActualApiExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
