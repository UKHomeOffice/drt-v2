package services.exports.flights.templates

import drt.shared.CrunchApi.MillisSinceEpoch
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues
import uk.gov.homeoffice.drt.ports.Queues._


trait FlightsWithSplitsExport extends FlightsExport {
  def flightWithSplitsToCsvFields(fws: ApiFlightWithSplits,
                                  millisToLocalDateTimeString: MillisSinceEpoch => String): List[String] = {
    List(
      fws.apiFlight.flightCodeString,
      fws.apiFlight.flightCodeString,
      fws.apiFlight.Origin.toString,
      fws.apiFlight.Gate.getOrElse("") + "/" + fws.apiFlight.Stand.getOrElse(""),
      fws.apiFlight.displayStatus.description,
      millisToLocalDateTimeString(fws.apiFlight.Scheduled),
      fws.apiFlight.predictedTouchdown.map(p => millisToLocalDateTimeString(p)).getOrElse(""),
      fws.apiFlight.Estimated.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.Actual.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.EstimatedChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.ActualChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.differenceFromScheduled.map(_.toMinutes.toString).getOrElse(""),
      fws.apiFlight.PcpTime.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.bestPaxSource.flatMap(_.passengers.actual).map(_.toString).getOrElse(""),
    )
  }

  protected def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    val apiIsInvalid = if (fws.hasApi && !fws.hasValidApi) "Y" else ""
    val pcpPax = if (fws.apiFlight.Origin.isDomesticOrCta) "-" else fws.pcpPaxEstimate.getPcpPax.map(_.toString).getOrElse("0")
    flightWithSplitsToCsvFields(fws, millisToLocalDateTimeStringFn) ++
      List(pcpPax, apiIsInvalid) ++
      splitsForSources(fws)
  }

  override val headings: String = ArrivalExportHeadings.arrivalWithSplitsHeadings

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = flightWithSplitsToCsvRow(fws)
}
