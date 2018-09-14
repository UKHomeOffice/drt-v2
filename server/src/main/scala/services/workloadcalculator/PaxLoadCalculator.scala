package services.workloadcalculator

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{Arrival, _}

import scala.collection.immutable.{List, _}


object PaxLoadCalculator {
  val paxOffFlowRate = 20
  val oneMinute = 60000L
  type Load = Double

  case class PaxTypeAndQueueCount(paxAndQueueType: PaxTypeAndQueue, paxSum: Load)

  def flightPaxFlowProvider(splitRatioProvider: Arrival => Option[SplitRatios],
                            bestPax: Arrival => Int): Arrival => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    voyagePaxSplitsFlowOverTime(splitRatioProvider, bestPax)
  }

  def voyagePaxSplitsFlowOverTime(splitsRatioProvider: Arrival => Option[SplitRatios], bestPax: Arrival => Int)
                                 (flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val pcpStartTimeMillis = MilliDate(flight.PcpTime.getOrElse(0L)).millisSinceEpoch
    //TODO fix this get
    val splits = splitsRatioProvider(flight).get.splits
    val splitsOverTime: IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = minutesForHours(pcpStartTimeMillis, 1)
      .zip(paxDeparturesPerMinutes(bestPax(flight), paxOffFlowRate))
      .flatMap {
        case (m, paxInMinute) =>
          splits.map(splitRatio => (m, PaxTypeAndQueueCount(splitRatio.paxType, splitRatio.ratio * paxInMinute)))
      }

    splitsOverTime
  }

  def minutesForHours(timesMin: MillisSinceEpoch, hours: Int): NumericRange[MillisSinceEpoch] = timesMin until (timesMin + oneMinute * 60 * hours) by oneMinute

  def paxDeparturesPerMinutes(remainingPax: Int, departRate: Int): List[Int] = {
    if (remainingPax % departRate != 0)
      List.fill(remainingPax / departRate)(departRate) ::: remainingPax % departRate :: Nil
    else
      List.fill(remainingPax / departRate)(departRate)
  }
}
