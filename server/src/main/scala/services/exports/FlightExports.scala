package services.exports

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.{AgeRange, FlightManifestSummary, UnknownAge}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import uk.gov.homeoffice.drt.splits.ApiSplitsToSplitRatio
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

object FlightExports {
  private val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def flightWithSplitsToCsvFields(paxFeedSourceOrder: Seq[FeedSource]): Arrival => List[String] =
    arrival => List(
      arrival.flightCodeString,
      arrival.Terminal.toString,
      arrival.Origin.toString,
      arrival.Gate.getOrElse("") + "/" + arrival.Stand.getOrElse(""),
      arrival.displayStatus.description,
      millisToLocalDateTimeString(arrival.Scheduled),
      arrival.predictedTouchdown.map(p => millisToLocalDateTimeString(p)).getOrElse(""),
      arrival.Estimated.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.Actual.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.EstimatedChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.ActualChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.differenceFromScheduled.map(_.toMinutes.toString).getOrElse(""),
      arrival.PcpTime.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.MaxPax.map(_.toString).getOrElse("n/a"),
      arrival.bestPaxEstimate(paxFeedSourceOrder).passengers.actual.map(_.toString).getOrElse(""),
      arrival.bestPcpPaxEstimate(paxFeedSourceOrder).map(_.toString).getOrElse(""),
    )

  def apiIsInvalid(fws: ApiFlightWithSplits): String =
    if (fws.hasApi && !fws.hasValidApi) "Y" else ""

  def millisToLocalDateTimeString: MillisSinceEpoch => String =
    (millis: MillisSinceEpoch) => SDate(millis, europeLondonTimeZone).toLocalDateTimeString

  def splitsForSources(fws: ApiFlightWithSplits,
                       paxFeedSourceOrder: List[FeedSource],
                      ): List[String] =
    splitSources.flatMap((ss: SplitSource) => queueSplits(ArrivalExportHeadings.queueNamesInOrder, fws, ss, paxFeedSourceOrder))

  private def queueSplits(queueNames: Seq[Queue],
                          fws: ApiFlightWithSplits,
                          splitSource: SplitSource,
                          paxFeedSourceOrder: List[FeedSource],
                         ): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource, paxFeedSourceOrder).getOrElse(q, "")}")

  private def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits,
                                           splitSource: SplitSource,
                                           paxFeedSourceOrder: List[FeedSource],
                                          ): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws, paxFeedSourceOrder))
      .getOrElse(Map())

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Iterable[String]): Iterable[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap
      .getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

  def nationalitiesFromSummary(maybeSummary: Option[FlightManifestSummary]): String =
    maybeSummary.map {
      _.nationalities
        .toList
        .sortBy { case (nat, paxCount) =>
          f"$paxCount%03d-${nat.code.getBytes.map(265 - _).mkString("-")}"
        }
        .reverseMap {
          case (nat, pax) => s"${nat.toString()}:$pax"
        }
        .mkString(",")
    }.getOrElse("")

  def ageRangesFromSummary(maybeSummary: Option[FlightManifestSummary]): String =
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
