package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.time.SDateLike


trait FlightsWithSplitsWithActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)

  def flightWithSplitsHeadingsPlusActualApi(queueNames: Seq[Queue]): String = arrivalWithSplitsHeadings(queueNames) + "," + actualApiHeadings.mkString(",") + ",Nationalities"

  override val headings: String = flightWithSplitsHeadingsPlusActualApi(queueNames)

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings).toList).map(s => s"$s") ::: List(s""""${nationalitiesFromManifest(maybeManifest)}"""")

  def nationalitiesFromManifest(maybeManifest: Option[VoyageManifest]): String =
    maybeManifest.map { manifest =>
      manifest.uniquePassengers
        .groupBy(_.nationality)
        .toList
        .sortBy { case (nat, pax) =>
          f"${pax.length}%03d-${nat.toString().getBytes.map(265 - _).mkString("-")}"
        }
        .reverseMap {
          case (nat, pax) => s"${nat.toString()}:${pax.length}"
        }
        .mkString(",")
    }.getOrElse("")
}

case class FlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
