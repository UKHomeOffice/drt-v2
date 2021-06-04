package services.exports.flights.templates

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared.splits.ApiSplitsToSplitRatio
import drt.shared.{ApiFlightWithSplits, PaxTypesAndQueues}
import org.joda.time.DateTimeZone
import services.SDate
import services.exports.Exports

trait FlightExportTemplate {

  val timeZone: DateTimeZone

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  def headings: String

  def rowValues(fws: ApiFlightWithSplits): Seq[String]

  def row(fws: ApiFlightWithSplits): String = rowValues(fws).mkString(",")

  def millisToDateStringFn: MillisSinceEpoch => String = SDate.millisToLocalIsoDateOnly(timeZone)

  def millisToTimeStringFn: MillisSinceEpoch => String = SDate.millisToLocalHoursAndMinutes(timeZone)

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def queueSplits(queueNames: Seq[Queue],
                  fws: ApiFlightWithSplits,
                  splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws))
      .getOrElse(Map())

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

}
