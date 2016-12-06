package services.workloadcalculator

import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import spatutorial.shared.FlightsApi.{QueueName, QueueWorkloads}
import spatutorial.shared._
import scala.collection.immutable.{IndexedSeq, Nil}


object PassengerQueueTypes {
  type FlightCode = String
}

object PaxLoadCalculator {
  val log = LoggerFactory.getLogger(getClass)
  val paxOffFlowRate = 20
  val oneMinute = 60000L
  type MillisSinceEpoch = Long
  type PaxSum = Double
  type WorkSum = Double
  type ProcTime = Double

  case class PaxTypeAndQueueCount(paxAndQueueType: PaxTypeAndQueue, paxSum: PaxSum)

  def queueWorkloadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (ApiFlight) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                              , procTimeProvider: (PaxTypeAndQueue) => ProcTime)(flights: List[ApiFlight]): Map[QueueName, QueueWorkloads] = {
    val flightsPaxSplits = flights.flatMap(calcPaxTypeAndQueueCountForAFlightOverTime)
    val flightsPaxSplitsByQueueAndMinute = flightsPaxSplits.groupBy(t => (t._2.paxAndQueueType.queueType, t._1))

    val loadsByQueueAndMinute: Map[(QueueName, MillisSinceEpoch), (PaxSum, WorkSum)] = flightsPaxSplitsByQueueAndMinute
      .mapValues { tmPtQcs => calcAndSumPaxAndWorkLoads(procTimeProvider, tmPtQcs.map(_._2)) }

    val loadsByQueue: Map[QueueName, Seq[(WL, Pax)]] = loadsByQueueAndMinute.toSeq.map {
      case ((queueName, time), (paxSum, workSum)) => (queueName, (WL(time, workSum), Pax(time, paxSum)))
    }.groupBy(_._1).mapValues(_.map(_._2))

    val queueWithLoads: Map[QueueName, (List[WL], List[Pax])] = loadsByQueue.mapValues(tuples => {
      val workLoads: List[WL] = sortLoadByTime(tuples.map(_._1))
      val paxLoads: List[Pax] = sortLoadByTime(tuples.map(_._2))
      (workLoads, paxLoads)
    })

    queueWithLoads
  }

  def calcAndSumPaxAndWorkLoads(procTimeProvider: (PaxTypeAndQueue) => ProcTime, paxTypeAndQueueCounts: List[PaxTypeAndQueueCount]): (PaxSum, WorkSum) = {
    val paxSum = paxTypeAndQueueCounts.map(_.paxSum).sum
    val workloadSum = paxTypeAndQueueCounts.map(ptQc => procTimeProvider(ptQc.paxAndQueueType) * ptQc.paxSum).sum
    (paxSum, workloadSum)
  }

  def sortLoadByTime[B <: Time](load: Seq[B]): List[B] = {
    load.sortBy(_.time).toList
  }

  def voyagePaxSplitsFlowOverTime(splitsRatioProvider: (ApiFlight) => Option[List[SplitRatio]])(flight: ApiFlight): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val timesMin = new DateTime(flight.SchDT, DateTimeZone.UTC).getMillis
    val splits = splitsRatioProvider(flight).get
    val splitsOverTime: IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = minsForNextNHours(timesMin, 1)
      .zip(paxDeparturesPerMinutes(if (flight.ActPax > 0) flight.ActPax else flight.MaxPax, paxOffFlowRate))
      .flatMap {
        case (m, paxInMinute) =>
          splits.map(splitRatio => (m, PaxTypeAndQueueCount(splitRatio.paxType, splitRatio.ratio * paxInMinute)))
      }

    splitsOverTime
  }

  def minsForNextNHours(timesMin: MillisSinceEpoch, hours: Int) = timesMin until (timesMin + oneMinute * 60 * hours) by oneMinute

  def paxDeparturesPerMinutes(remainingPax: Int, departRate: Int): List[Int] = {
    if (remainingPax % departRate != 0)
      List.fill(remainingPax / departRate)(departRate) ::: remainingPax % departRate :: Nil
    else
      List.fill(remainingPax / departRate)(departRate)
  }
}