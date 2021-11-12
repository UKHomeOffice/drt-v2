package services.exports.flights.templates

import drt.shared.ApiFlightWithSplits
import drt.shared.CrunchApi.MillisSinceEpoch
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource


trait FlightsWithSplitsExport extends FlightsExport {
  val arrivalHeadings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax"

  val actualApiHeadings: Seq[String] = List(
    PaxTypeAndQueue(B5JPlusNational, EeaDesk),
    PaxTypeAndQueue(B5JPlusNational, EGate),
    PaxTypeAndQueue(B5JPlusNationalBelowEGateAge, EeaDesk),
    PaxTypeAndQueue(EeaMachineReadable, EeaDesk),
    PaxTypeAndQueue(EeaMachineReadable, EGate),
    PaxTypeAndQueue(EeaNonMachineReadable, EeaDesk),
    PaxTypeAndQueue(EeaBelowEGateAge, EeaDesk),
    PaxTypeAndQueue(NonVisaNational, FastTrack),
    PaxTypeAndQueue(VisaNational, FastTrack),
    PaxTypeAndQueue(NonVisaNational, NonEeaDesk),
    PaxTypeAndQueue(VisaNational, NonEeaDesk),
    PaxTypeAndQueue(Transit, Transfer),
  ).map(pq => s"API Actual - ${pq.displayName}")

  private def headingsForSplitSource(queueNames: Seq[Queue], source: String): String = queueNames
    .map(q => s"$source ${Queues.displayName(q)}")
    .mkString(",")

  def arrivalWithSplitsHeadings(queueNames: Seq[Queue]): String =
    arrivalHeadings + ",PCP Pax,Invalid API," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")


  def flightWithSplitsToCsvFields(fws: ApiFlightWithSplits, millisToDateOnly: MillisSinceEpoch => String,
                                  millisToHoursAndMinutes: MillisSinceEpoch => String): List[String] =
    List(fws.apiFlight.flightCodeString,
      fws.apiFlight.flightCodeString,
      fws.apiFlight.Origin.toString,
      fws.apiFlight.Gate.getOrElse("") + "/" + fws.apiFlight.Stand.getOrElse(""),
      fws.apiFlight.displayStatus.description,
      millisToDateOnly(fws.apiFlight.Scheduled),
      millisToHoursAndMinutes(fws.apiFlight.Scheduled),
      fws.apiFlight.Estimated.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.Actual.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.EstimatedChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.ActualChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.PcpTime.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.totalPax.map(_.toString).getOrElse(""),
    )

  protected def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    val apiIsInvalid = fws.hasApi && !fws.hasValidApi
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    val pcpPax = if (fws.apiFlight.Origin.isDomesticOrCta) "-" else fws.pcpPaxEstimate.toString
    flightWithSplitsToCsvFields(fws, millisToDateStringFn, millisToTimeStringFn) ++
      List(pcpPax, if (apiIsInvalid) "Y" else "") ++ splitsForSources
  }

  override val headings: String = arrivalWithSplitsHeadings(queueNames)

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = flightWithSplitsToCsvRow(fws)
}
