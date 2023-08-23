package passengersplits

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.TQM
import org.slf4j.LoggerFactory
import services.graphstages.Crunch.LoadMinute
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, FeedSource, PaxType, PaxTypeAndQueue}
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.annotation.tailrec
import scala.collection.immutable.NumericRange

object WholePassengerQueueSplits {
  private val log = LoggerFactory.getLogger(getClass)

  def splits(minuteMillis: NumericRange[MillisSinceEpoch],
             flights: Iterable[ApiFlightWithSplits],
             processingTime: Terminal => (PaxType, Queue) => Double,
             queueStatus: Terminal => (Queue, MillisSinceEpoch) => QueueStatus,
             queueFallbacks: QueueFallbacks,
             paxFeedSourceOrder: List[FeedSource],
            ): Map[TQM, LoadMinute] =
    flights
      .groupBy(_.apiFlight.Terminal)
      .toList
      .flatMap {
        case (terminal, flights) =>
          val procTimes = processingTime(terminal)
          flights
            .flatMap { flight =>
              flightSplits(minuteMillis, flight, procTimes, queueStatus(flight.apiFlight.Terminal), queueFallbacks, paxFeedSourceOrder)
                .flatMap { case (queue, byMinute) =>
                  byMinute.map {
                    case (minute, passengers) =>
                      (TQM(terminal, queue, minute), passengers)
                  }
                }
            }
      }
      .groupBy { case (tqm, _) => tqm }
      .map {
        case (tqm, passengers) =>
          val loadsByPassenger = passengers.flatMap { case (_, paxLoads) => paxLoads }
          val totalLoad = loadsByPassenger.sum
          (tqm, LoadMinute(tqm.terminal, tqm.queue, loadsByPassenger, totalLoad, tqm.minute))
      }

  def flightSplits(minuteMillis: NumericRange[MillisSinceEpoch],
                   flight: ApiFlightWithSplits,
                   processingTime: (PaxType, Queue) => Double,
                   queueStatus: (Queue, MillisSinceEpoch) => QueueStatus,
                   queueFallbacks: QueueFallbacks,
                   paxFeedSourceOrder: List[FeedSource],
                  ): Map[Queue, Map[MillisSinceEpoch, List[Double]]] =
    flight.bestSplits match {
      case Some(splitsToUse) =>
        val pcpPax = flight.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)
        val startMinute = SDate(flight.apiFlight.pcpRange(paxFeedSourceOrder).min)
        val terminalQueueFallbacks = (q: Queue, pt: PaxType) => queueFallbacks.availableFallbacks(flight.apiFlight.Terminal, q, pt).toList
        val wholePaxSplits = wholePassengerSplits(pcpPax, splitsToUse.splits)
        wholePaxLoadsPerQueuePerMinute(minuteMillis, pcpPax, wholePaxSplits, processingTime, queueStatus, terminalQueueFallbacks, startMinute)
      case None =>
        log.error(s"No splits found for ${flight.apiFlight.flightCode}")
        Map.empty
    }

  @tailrec
  private def maybeFallbackQueue(queueStatus: (Queue, MillisSinceEpoch) => QueueStatus,
                                 queueFallbacks: List[Queue],
                                 minute: MillisSinceEpoch,
                                ): Option[Queue] =
    queueFallbacks match {
      case Nil => None
      case queue :: tail =>
        queueStatus(queue, minute) match {
          case Open => Option(queue)
          case _ => maybeFallbackQueue(queueStatus, tail, minute)
        }
    }

  def wholePaxLoadsPerQueuePerMinute(processingWindow: NumericRange[MillisSinceEpoch],
                                     totalPax: Int,
                                     wholeSplits: Seq[ApiPaxTypeAndQueueCount],
                                     processingTime: (PaxType, Queue) => Double,
                                     queueStatus: (Queue, MillisSinceEpoch) => QueueStatus,
                                     queueFallbacks: (Queue, PaxType) => List[Queue],
                                     startMinute: SDateLike,
                                    ): Map[Queue, Map[MillisSinceEpoch, List[Double]]] = {
    val windowStart = processingWindow.min
    val windowEnd = processingWindow.max
    val paxDistribution = distributePaxOverSplitsAndMinutes(totalPax, 20, wholeSplits.map(s => (s.paxTypeAndQueue, s.paxCount.toInt)).toMap)
    val workloadDistribution = paxDistributionToWorkloads(paxDistribution, processingTime, startMinute.millisSinceEpoch, queueStatus, queueFallbacks)
    val relevantMinutes = workloadDistribution.view.filterKeys(m => windowStart <= m && m <= windowEnd)
    invertWorkload(relevantMinutes.toMap)
  }

  def distributePaxOverSplitsAndMinutes(passengerCount: Int,
                                        passengersPerMinute: Int,
                                        paxSplits: Map[PaxTypeAndQueue, Int],
                                       ): Map[Int, Map[PaxTypeAndQueue, Int]] = {
    val groups = (passengerCount.toDouble / passengersPerMinute).ceil.toInt
    val splitPcts = paxSplits.view.mapValues(s => s.toDouble / passengerCount).toMap
    val emptySplitCounts: Map[PaxTypeAndQueue, Int] = paxSplits.keys.map(k => k -> 0).toMap
    val emptyMinutesOfPax: Map[Int, Map[PaxTypeAndQueue, Int]] = (1 to groups).map(g => g -> emptySplitCounts).toMap
    (1 to groups).foldLeft(emptyMinutesOfPax) {
      case (acc, group) =>
        val alreadyDistributed = acc.foldLeft(emptySplitCounts) {
          case (acc, (_, splitCounts)) =>
            splitCounts.foldLeft(acc) { case (acc, (split, count)) =>
              val newCount = acc(split) + count
              acc + (split -> newCount)
            }
        }
        val paxSoFar = if (group == groups) passengerCount else group * passengersPerMinute
        val pctSoFar = paxSoFar.toDouble / passengerCount
        val newPaxForGroup: Map[PaxTypeAndQueue, Int] = splitPcts.map { case (split, pct) =>
          val paxForSplit = (pctSoFar * pct * passengerCount).round.toInt
          val previousPaxForSplit = alreadyDistributed(split)
          (split, paxForSplit - previousPaxForSplit)
        }
        acc + (group -> newPaxForGroup)
    }
  }

  def paxDistributionToWorkloads(distribution: Map[Int, Map[PaxTypeAndQueue, Int]],
                                 processingTime: (PaxType, Queue) => Double,
                                 startMillis: MillisSinceEpoch,
                                 queueStatus: (Queue, MillisSinceEpoch) => QueueStatus,
                                 queueFallbacks: (Queue, PaxType) => List[Queue],
                                ): Map[MillisSinceEpoch, Map[Queue, List[Double]]] =
    distribution.map { case (minute, splitCounts) =>
      val millisecondMinute = startMillis + ((minute - 1) * oneMinuteMillis)
      val workloads = splitCounts.foldLeft(Map[Queue, List[Double]]()) { case (acc, (PaxTypeAndQueue(paxType, queue), count)) =>
        val finalQueue = if (queueStatus(queue, millisecondMinute) == Open)
          queue
        else
          queueFallbacks(queue, paxType).find(queueStatus(_, millisecondMinute) == Open).getOrElse {
            log.error(s"No fallback for closed queue ${queue} at ${SDate(millisecondMinute).toISOString}. Resorting to closed queue")
            queue
          }
        val time = processingTime(paxType, finalQueue)
        val existingQueueLoad = acc.getOrElse(finalQueue, List.empty[Double])
        val newQueueLoad = existingQueueLoad ++ List.fill(count)(time)
        acc + (finalQueue -> newQueueLoad)
      }
      (millisecondMinute, workloads)
    }

  def invertWorkload(workload: Map[MillisSinceEpoch, Map[Queue, List[Double]]]): Map[Queue, Map[MillisSinceEpoch, List[Double]]] =
    workload.foldLeft(Map[Queue, Map[MillisSinceEpoch, List[Double]]]()) { case (acc, (millis, queueWorkloads)) =>
      queueWorkloads.foldLeft(acc) { case (acc, (queue, workloads)) =>
        val existingQueueWorkloads = acc.getOrElse(queue, Map[MillisSinceEpoch, List[Double]]())
        val newQueueWorkloads = existingQueueWorkloads + (millis -> workloads)
        acc + (queue -> newQueueWorkloads)
      }
    }
  //  def wholePaxLoadsPerQueuePerMinute_old(processingWindow: NumericRange[MillisSinceEpoch],
  //                                     totalPax: Int,
  //                                     wholeSplits: Seq[ApiPaxTypeAndQueueCount],
  //                                     processingTime: (PaxType, Queue) => Double,
  //                                     queueStatus: (Queue, MillisSinceEpoch) => QueueStatus,
  //                                     queueFallbacks: (Queue, PaxType) => List[Queue],
  //                                     startMinute: SDateLike,
  //                                    ): Map[Queue, Map[MillisSinceEpoch, List[Double]]] = {
  //    val windowStart = processingWindow.min
  //    val windowEnd = processingWindow.max
  //    wholeSplits
  //      .toList
  //      .flatMap { ptqc =>
  //        val procTime = processingTime(ptqc.passengerType, ptqc.queueType)
  //        val loadsByMinute = paxLoadsPerMinute(totalPax, ptqc.paxCount.toInt, 20, procTime)
  //        val loadsByMinuteInWindow = loadsByMinute
  //          .filter { case (minute, _) =>
  //            val m = startMinute.addMinutes(minute - 1).millisSinceEpoch
  //            windowStart <= m && m <= windowEnd
  //          }
  //        loadsByMinuteInWindow
  //          .map { case (minute, passengerLoads) =>
  //            val minuteMillis = startMinute.addMinutes(minute - 1).millisSinceEpoch
  //            if (queueStatus(ptqc.queueType, minuteMillis) != Open) {
  //              val fallbacks = queueFallbacks(ptqc.queueType, ptqc.passengerType)
  //              val redirectedQueue = maybeFallbackQueue(queueStatus, fallbacks, minuteMillis).getOrElse {
  //                log.error(s"No fallback for closed queue ${ptqc.queueType} at ${SDate(minuteMillis).toISOString}. Resorting to closed queue")
  //                ptqc.queueType
  //              }
  //              (redirectedQueue, minuteMillis, passengerLoads)
  //            }
  //            else (ptqc.queueType, minuteMillis, passengerLoads)
  //          }
  //      }
  //      .groupBy(_._1)
  //      .map {
  //        case (queue, loads) =>
  //          val loadsByMinute = loads
  //            .groupBy(_._2)
  //            .map {
  //              case (minute, loads) => (minute, loads.flatMap(_._3))
  //            }
  //          (queue, loadsByMinute)
  //      }
  //  }

  def wholePassengerSplits(totalPax: Int, splits: Set[ApiPaxTypeAndQueueCount]): Seq[ApiPaxTypeAndQueueCount] = {
    val splitsMinusTransferInOrder = splits.toList
      .sortBy(_.paxCount)
      .filterNot(_.queueType == Transfer)
    val totalSplitsPax = splits.toList.map(_.paxCount).sum
    val totalSplits = splitsMinusTransferInOrder.size

    splitsMinusTransferInOrder.foldLeft(Seq[ApiPaxTypeAndQueueCount]()) {
      case (actualSplits, nextSplit) =>
        val countSoFar = actualSplits.toList.map(_.paxCount).sum
        val proposedCount = Math.round((nextSplit.paxCount / totalSplitsPax) * totalPax).toInt

        val finalSplit = actualSplits.size == totalSplits - 1
        val actualCount = if (proposedCount + countSoFar <= totalPax && !finalSplit)
          proposedCount
        else totalPax - countSoFar

        actualSplits :+ nextSplit.copy(paxCount = actualCount)
    }
  }

  def paxLoadsPerMinute(totalPassengers: Int, queuePassengers: Int, paxOffRate: Int, loadPerPax: Double): Map[Int, List[Double]] = {
    val minutesOff = (totalPassengers.toDouble / paxOffRate).ceil.toInt
    val paxPerMinute = queuePassengers.toDouble / minutesOff

    val paxLoadsByMinute = (1 to minutesOff).foldLeft(Map[Int, List[Double]]()) {
      case (paxLoadsAcc, minute) =>
        val roundedSummedPaxForMinute = (paxPerMinute * minute).ceil.toInt
        val paxSoFar = paxCountFromLoads(paxLoadsAcc.values)
        val paxThisMinute = roundedSummedPaxForMinute - paxSoFar
        val loads = List.fill(paxThisMinute)(loadPerPax)
        paxLoadsAcc.updated(minute, loads)
    }

    if (paxCountFromLoads(paxLoadsByMinute.values) < queuePassengers) {
      val finalMinutePax = queuePassengers - paxCountFromLoads(paxLoadsByMinute.values)
      paxLoadsByMinute.updated(paxLoadsByMinute.size + 1, List.fill(finalMinutePax)(loadPerPax))
    }
    else paxLoadsByMinute
  }

  private def paxCountFromLoads(paxByMinute: Iterable[List[Double]]): Int = paxByMinute.map(_.size).sum
}
