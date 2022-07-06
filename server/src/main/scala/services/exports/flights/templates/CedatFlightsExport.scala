package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import drt.shared.CrunchApi.MillisSinceEpoch
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable


case class CedatFlightsExport(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsExport {

  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)

  val arrivalHeadings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax"

  val actualApiHeadings: immutable.Seq[String] = PaxTypesAndQueues.inOrder.map(heading => s"API Actual - ${heading.displayName}")

  def arrivalHeadings(queueNames: Seq[Queue]): String =
    arrivalHeadings + ",PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  def allHeadings(queueNames: Seq[Queue]): String = arrivalHeadings(queueNames) + "," + actualApiHeadings.mkString(",")

  def arrivalAsRawCsvValues(arrival: Arrival,
                            millisToDateOnly: MillisSinceEpoch => String,
                            millisToHoursAndMinutes: MillisSinceEpoch => String): List[String] =
    List(arrival.flightCodeString,
      arrival.flightCodeString,
      arrival.Origin.toString,
      arrival.Gate.getOrElse("") + "/" + arrival.Stand.getOrElse(""),
      arrival.displayStatus.description,
      millisToDateOnly(arrival.Scheduled),
      millisToHoursAndMinutes(arrival.Scheduled),
      arrival.Estimated.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.Actual.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.EstimatedChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.ActualChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.PcpTime.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.ActPax.map(_.toString).getOrElse(""),
    )

  override val headings: String = allHeadings(queueNames)

  def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    arrivalAsRawCsvValues(
      fws.apiFlight,
      millisToDateStringFn,
      millisToTimeStringFn
    ) ++ List(fws.apiFlight.bestPcpPaxEstimate.pax.map(_.toString).getOrElse("")) ++ splitsForSources(fws)
  }

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Iterable[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings).toList).map(s => s"$s")

  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
