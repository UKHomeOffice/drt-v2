package services.workloadcalculator

import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import services.SDate
import drt.shared.FlightsApi.{QueueName, QueuePaxAndWorkLoads}
import drt.shared.Queues.QueueType
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared._

import scala.List
import scala.collection.immutable._


object PassengerQueueTypes {
  type FlightCode = String
}

object PaxLoadCalculator {
  val log = LoggerFactory.getLogger(getClass)
  val paxOffFlowRate = 20
  val oneMinute = 60000L
  type MillisSinceEpoch = Long
  type Load = Double
  type ProcTime = Double

  case class PaxTypeAndQueueCount(paxAndQueueType: PaxTypeAndQueue, paxSum: Load)

  def calcAndSumPaxAndWorkLoads(procTimeProvider: (PaxTypeAndQueue) => ProcTime, paxTypeAndQueueCounts: List[PaxTypeAndQueueCount]): (Load, Load) = {
    val paxSum = paxTypeAndQueueCounts.map(_.paxSum).sum
    val workloadSum = paxTypeAndQueueCounts.map(ptQc => procTimeProvider(ptQc.paxAndQueueType) * ptQc.paxSum).sum
    (paxSum, workloadSum)
  }

  def queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)],
                          calcAndSumLoads: List[PaxTypeAndQueueCount] => Load)
                         (flights: List[ApiFlight]): Map[QueueType, List[(MillisSinceEpoch, Load)]] = {
    val flightsPaxSplits = flights.flatMap(calcPaxTypeAndQueueCountForAFlightOverTime)
    val flightsPaxSplitsByQueueAndMinute = flightsPaxSplits.groupBy(t => (t._2.paxAndQueueType.queueType, t._1))

    val loadsByQueueAndMinute: Map[(QueueType, MillisSinceEpoch), Load] = flightsPaxSplitsByQueueAndMinute
      .mapValues { tmPtQcs => calcAndSumLoads(tmPtQcs.map(_._2)) }

    val workLoadsByQueue: List[(QueueType, (MillisSinceEpoch, Load))] = loadsByQueueAndMinute.toList.map {
      case ((queueName, time), workSum) => (queueName, (time, workSum))
    }

    workLoadsByQueue.groupBy(_._1).mapValues(extractMillisAndLoadSortedByMillis(_))
  }

  def extractMillisAndLoadSortedByMillis(queueNameMillisLoad: List[(QueueType, (MillisSinceEpoch, Load))]): List[(MillisSinceEpoch, Load)] = {
    queueNameMillisLoad.map(_._2).sortBy(_._1)
  }

  def queueWorkAndPaxLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                                    , procTimeProvider: (PaxTypeAndQueue) => ProcTime)(flights: List[ApiFlight]): Map[QueueType, QueuePaxAndWorkLoads] = {
    val queuePaxLoads = queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumPaxLoads)(flights)
    val queueWorkLoads = queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumWorkLoads(procTimeProvider))(flights)

    queuePaxLoads.keys.map(queueName => {(queueName, (
        queueWorkLoads(queueName).map(tuple => WL(tuple._1, tuple._2)),
        queuePaxLoads(queueName).map(tuple => Pax(tuple._1, tuple._2))))
    }).toMap
  }

  def queueWorkLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                              , procTimeProvider: (PaxTypeAndQueue) => ProcTime)(flights: List[ApiFlight]): Map[QueueType, Seq[WL]] = {
    queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumWorkLoads(procTimeProvider))(flights)
      .mapValues(millisAndLoadsToWorkLoads(ml => WL(ml._1, ml._2)))
  }

  def millisAndLoadsToWorkLoads[B <: Time](constructLoad: ((MillisSinceEpoch, Load)) => B): (List[(MillisSinceEpoch, Load)]) => List[B] = {
    x => x.map(constructLoad)
  }

  def queuePaxLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                             , procTimeProvider: (PaxTypeAndQueue) => ProcTime)(flights: List[ApiFlight]): Map[QueueType, Seq[Pax]] = {
    queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumPaxLoads)(flights)
      .mapValues(millisAndLoadsToWorkLoads(ml => Pax(ml._1, ml._2)))
  }

  def calcAndSumPaxLoads(paxTypeAndQueueCounts: List[PaxTypeAndQueueCount]): Load = {
    paxTypeAndQueueCounts.map(_.paxSum).sum
  }

  def calcAndSumWorkLoads(procTimeProvider: (PaxTypeAndQueue) => ProcTime)(paxTypeAndQueueCounts: List[PaxTypeAndQueueCount]): Load = {
    paxTypeAndQueueCounts.map(ptQc => procTimeProvider(ptQc.paxAndQueueType) * ptQc.paxSum).sum
  }

  def voyagePaxSplitsFlowOverTime(splitsRatioProvider: (ApiFlight) => Option[SplitRatios])(flight: ApiFlight): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val timesMin = SDate.parseString(flight.SchDT).millisSinceEpoch
    val splits = splitsRatioProvider(flight).get.splits
    val splitsOverTime: IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = minutesForHours(timesMin, 1)
      .zip(paxDeparturesPerMinutes(if (flight.ActPax > 0) flight.ActPax else flight.MaxPax, paxOffFlowRate))
      .flatMap {
        case (m, paxInMinute) =>
          splits.map(splitRatio => (m, PaxTypeAndQueueCount(splitRatio.paxType, splitRatio.ratio * paxInMinute)))
      }

    splitsOverTime
  }


  def minutesForHours(timesMin: MillisSinceEpoch, hours: Int) = timesMin until (timesMin + oneMinute * 60 * hours) by oneMinute

  def paxDeparturesPerMinutes(remainingPax: Int, departRate: Int): List[Int] = {
    if (remainingPax % departRate != 0)
      List.fill(remainingPax / departRate)(departRate) ::: remainingPax % departRate :: Nil
    else
      List.fill(remainingPax / departRate)(departRate)
  }
}
