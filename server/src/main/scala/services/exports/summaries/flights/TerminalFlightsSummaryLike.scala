package services.exports.summaries.flights

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared._
import drt.shared.api.Arrival
import drt.shared.splits.ApiSplitsToSplitRatio
import services.exports.summaries.TerminalSummaryLike

object TerminalFlightsSummaryLike {
  type TerminalFlightsSummaryLikeGenerator = (Seq[ApiFlightWithSplits], MillisSinceEpoch => String, MillisSinceEpoch => String, Arrival => Int) => TerminalSummaryLike
}

trait TerminalFlightsSummaryLike extends TerminalSummaryLike {
  override def isEmpty: Boolean = flights.isEmpty

  def flights: Seq[ApiFlightWithSplits]

  def pcpPaxFn: Arrival => Int

  def millisToDateOnly: MillisSinceEpoch => String

  def millisToHoursAndMinutes: MillisSinceEpoch => String

  def csvHeader: String

  def headingsForSplitSource(queueNames: Seq[Queue], source: String): String = queueNames
    .map(q => {
      val queueName = Queues.queueDisplayNames(q)
      s"$source $queueName"
    })
    .mkString(",")

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))

  def flightWithSplitsToCsvRow(queueNames: Seq[Queue], fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    TerminalFlightsSummary.arrivalAsRawCsvValues(fws.apiFlight, millisToDateOnly, millisToHoursAndMinutes) ++
      List(pcpPaxFn(fws.apiFlight).toString) ++ splitsForSources
  }

  def queueSplits(queueNames: Seq[Queue],
                  fws: ApiFlightWithSplits,
                  splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws.apiFlight, pcpPaxFn))
      .getOrElse(Map())

  def asCSV(csvData: Iterable[List[Any]]): String =
    if (csvData.nonEmpty)
      csvData.map(_.mkString(",")).mkString(lineEnding)
    else lineEnding
}
