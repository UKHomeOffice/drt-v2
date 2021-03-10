package services.workloadcalculator

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared._
import drt.shared.api.Arrival

import scala.collection.immutable.{List, _}


object PaxLoadCalculator {
  val paxOffFlowRate = 20
  val oneMinute = 60000L
  type Load = Double

  case class PaxTypeAndQueueCount(paxAndQueueType: PaxTypeAndQueue, paxSum: Load)

  def minutesForHours(timesMin: MillisSinceEpoch, hours: Int): NumericRange[MillisSinceEpoch] = timesMin until (timesMin + oneMinute * 60 * hours) by oneMinute
}
