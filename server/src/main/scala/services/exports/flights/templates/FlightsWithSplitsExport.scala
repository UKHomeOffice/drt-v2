package services.exports.flights.templates

import drt.shared.CrunchApi.MillisSinceEpoch
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues
import uk.gov.homeoffice.drt.ports.Queues._


trait FlightsWithSplitsExport extends FlightsExport {
  val arrivalHeadings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled,Predicted Arrival,Est Arrival,Act Arrival,Est Chox,Act Chox,Minutes off scheduled,Est PCP,Total Pax"

  val actualApiHeadings: Seq[String] = PaxTypesAndQueues.inOrder.map(heading => s"API Actual - ${heading.displayName}")

  def arrivalWithSplitsHeadings(queueNames: Seq[Queue]): String =
    arrivalHeadings + ",PCP Pax,Invalid API," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  def flightWithSplitsToCsvFields(fws: ApiFlightWithSplits,
                                  millisToLocalDateTimeString: MillisSinceEpoch => String): List[String] = {
    List(
      fws.apiFlight.flightCodeString,
      fws.apiFlight.flightCodeString,
      fws.apiFlight.Origin.toString,
      fws.apiFlight.Gate.getOrElse("") + "/" + fws.apiFlight.Stand.getOrElse(""),
      fws.apiFlight.displayStatus(false).description,
      millisToLocalDateTimeString(fws.apiFlight.Scheduled),
      fws.apiFlight.predictedTouchdown.map(p => millisToLocalDateTimeString(p)).getOrElse(""),
      fws.apiFlight.Estimated.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.Actual.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.EstimatedChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.ActualChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.apiFlight.differenceFromScheduled.map(_.toMinutes.toString).getOrElse(""),
      fws.apiFlight.PcpTime.map(millisToLocalDateTimeString(_)).getOrElse(""),
      fws.totalPax.flatMap(_.pax).map(_.toString).getOrElse(""),
    )
  }

  protected def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    val apiIsInvalid = if (fws.hasApi && !fws.hasValidApi) "Y" else ""
    val pcpPax = if (fws.apiFlight.Origin.isDomesticOrCta) "-" else fws.pcpPaxEstimate.pax.map(_.toString).getOrElse("0")
    flightWithSplitsToCsvFields(fws, millisToLocalDateTimeStringFn) ++
      List(pcpPax, apiIsInvalid) ++
      splitsForSources(fws)
  }

  override val headings: String = arrivalWithSplitsHeadings(queueNames)

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = flightWithSplitsToCsvRow(fws)
}
