package services.exports.summaries.flights

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared._
import drt.shared.splits.ApiSplitsToSplitRatio
import services.exports.summaries.TerminalSummaryLike

trait TerminalFlightsSummaryLike extends TerminalSummaryLike {
  def flights: Seq[ApiFlightWithSplits]

  def millisToDateOnly: MillisSinceEpoch => String

  def millisToHoursAndMinutes: MillisSinceEpoch => String

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  def csvHeader: String

  def headingsForSplitSource(queueNames: Seq[Queue], source: String): String = queueNames
    .map(q => {
      val queueName = Queues.queueDisplayNames(q)
      s"$source $queueName"
    })
    .mkString(",")

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def flightToCsvRow(queueNames: Seq[Queue], fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap(ss => queueSplits(queueNames, fws, ss))

    List(
      fws.apiFlight.flightCode,
      fws.apiFlight.flightCode,
      fws.apiFlight.Origin.toString,
      fws.apiFlight.Gate.getOrElse("") + "/" + fws.apiFlight.Stand.getOrElse(""),
      fws.apiFlight.Status.description,
      millisToDateOnly(fws.apiFlight.Scheduled),
      millisToHoursAndMinutes(fws.apiFlight.Scheduled),
      fws.apiFlight.Estimated.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.Actual.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.EstimatedChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.ActualChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.PcpTime.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.ActPax.getOrElse(0).toString,
      ArrivalHelper.bestPax(fws.apiFlight).toString
      ) ++ splitsForSources
  }

  private def queueSplits(queueNames: Seq[Queue],
                          fws: ApiFlightWithSplits,
                          splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws.apiFlight))
      .getOrElse(Map())

  def asCSV(csvData: Iterable[List[Any]]): String = csvData.map(_.mkString(",")).mkString(lineEnding)
}
