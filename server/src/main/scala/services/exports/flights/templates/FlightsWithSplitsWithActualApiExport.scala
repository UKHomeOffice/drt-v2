package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import drt.shared.api.{AgeRange, PassengerInfoSummary, UnknownAge}
import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.time.SDateLike


trait FlightsWithSplitsWithActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)

  override val headings: String = ArrivalExportHeadings.arrivalWithSplitsAndRawApiHeadings

  override def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Seq[String] = {
    val maybePaxSummary = maybeManifest.flatMap(PassengerInfo.manifestToPassengerInfoSummary)

    (flightWithSplitsToCsvRow(fws) :::
      actualAPISplitsForFlightInHeadingOrder(fws, ArrivalExportHeadings.actualApiHeadings).toList).map(s => s"$s") :::
      List(s""""${nationalitiesFromSummary(maybePaxSummary)}"""", s""""${ageRangesFromSummary(maybePaxSummary)}"""")
  }

  def nationalitiesFromSummary(maybeSummary: Option[PassengerInfoSummary]): String =
    maybeSummary.map {
      _.nationalities
        .toList
        .sortBy { case (nat, paxCount) =>
          f"${paxCount}%03d-${nat.code.getBytes.map(265 - _).mkString("-")}"
        }
        .reverseMap {
          case (nat, pax) => s"${nat.toString()}:${pax}"
        }
        .mkString(",")
    }.getOrElse("")

  def ageRangesFromSummary(maybeSummary: Option[PassengerInfoSummary]): String =
    maybeSummary.map {
      _.ageRanges
        .toList
        .sortBy {
          case (AgeRange(bottom, _), _) => bottom
          case (UnknownAge, _) => 1000
        }
        .map {
          case (ageRange, pax) => s"${ageRange.title}:$pax"
        }
        .mkString(",")
    }.getOrElse("")

}

case class FlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
