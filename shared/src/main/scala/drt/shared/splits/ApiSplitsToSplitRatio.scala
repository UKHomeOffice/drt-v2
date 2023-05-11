package drt.shared.splits

import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, SplitStyle, Splits}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxTypeAndQueue, Queues}


object ApiSplitsToSplitRatio {

  def queuesFromPaxTypeAndQueue(ptq: Seq[PaxTypeAndQueue]): Seq[Queue] = ptq.map {
    case PaxTypeAndQueue(_, q) => q
  }.distinct

  def queueTotals(splits: Map[PaxTypeAndQueue, Int]): Map[Queue, Int] = splits
    .foldLeft(Map[Queue, Int]())((map, ptqc) => {
      ptqc match {
        case (PaxTypeAndQueue(_, q), pax) =>
          map + (q -> (map.getOrElse(q, 0) + pax))
      }
    })

  def paxPerQueueUsingBestSplitsAsRatio(flightWithSplits: ApiFlightWithSplits): Option[Map[Queue, Int]] =
    flightWithSplits.bestSplits.map(flightPaxPerQueueUsingSplitsAsRatio(_, flightWithSplits))

  def flightPaxPerQueueUsingSplitsAsRatio(splits: Splits, fws: ApiFlightWithSplits): Map[Queue, Int] =
    queueTotals(
      applyPaxSplitsToFlightPax(splits, fws.bestPaxSource.getPcpPax.getOrElse(0))
        .splits
        .map(ptqc => PaxTypeAndQueue(ptqc.passengerType, ptqc.queueType) -> ptqc.paxCount.toInt)
        .toMap
    )

  def applyPaxSplitsToFlightPax(apiSplits: Splits, totalPax: Int): Splits = {
    val splitsSansTransfer = apiSplits.splits.filter(_.queueType != Queues.Transfer)
    val splitsAppliedAsRatio = splitsSansTransfer.map(s => {
      val total = splitsPaxTotal(splitsSansTransfer)
      val paxCountRatio = applyRatio(s, totalPax, total)
      s.copy(paxCount = paxCountRatio)
    })
    apiSplits.copy(
      splitStyle = SplitStyle("Ratio"),
      splits = fudgeRoundingError(splitsAppliedAsRatio, totalPax - splitsPaxTotal(splitsAppliedAsRatio))
    )
  }

  def applyRatio(split: ApiPaxTypeAndQueueCount, totalPax: Int, splitsTotal: Double): Long =
    Math.round(totalPax * (split.paxCount / splitsTotal))

  def fudgeRoundingError(splits: Set[ApiPaxTypeAndQueueCount], diff: Double): Set[ApiPaxTypeAndQueueCount] =
    splits
      .toList
      .sortBy(_.paxCount)
      .reverse match {
      case head :: tail =>
        (head.copy(paxCount = head.paxCount + diff) :: tail).toSet
      case _ =>
        splits
    }

  def splitsPaxTotal(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.toSeq.map(_.paxCount).sum
}
