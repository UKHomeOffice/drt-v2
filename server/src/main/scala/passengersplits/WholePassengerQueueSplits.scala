package passengersplits

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.TQM
import org.slf4j.LoggerFactory
import services.graphstages.Crunch.LoadMinute
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, FeedSource, PaxType, PaxTypeAndQueue}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}

import scala.collection.immutable.NumericRange

object WholePassengerQueueSplits {
  private val log = LoggerFactory.getLogger(getClass)

  def splits(minuteMillis: NumericRange[MillisSinceEpoch],
             flights: Iterable[ApiFlightWithSplits],
             processingTime: Terminal => (PaxType, Queue) => Double,
             queueStatus: Terminal => (Queue, MillisSinceEpoch) => QueueStatus,
             queueFallbacks: QueueFallbacks,
             paxFeedSourceOrder: List[FeedSource],
             terminalSplits: Terminal => Option[Splits],
            ): Map[TQM, LoadMinute] =
    flights
      .groupBy(_.apiFlight.Terminal)
      .toList
      .flatMap {
        case (terminal, flights) =>
          val procTimes = processingTime(terminal)
          flights
            .flatMap { flight =>
              flightSplits(minuteMillis, flight, procTimes, queueStatus(flight.apiFlight.Terminal), queueFallbacks, paxFeedSourceOrder, terminalSplits)
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

  private def flightSplits(minuteMillis: NumericRange[MillisSinceEpoch],
                           flight: ApiFlightWithSplits,
                           processingTime: (PaxType, Queue) => Double,
                           queueStatus: (Queue, MillisSinceEpoch) => QueueStatus,
                           queueFallbacks: QueueFallbacks,
                           paxFeedSourceOrder: List[FeedSource],
                           terminalSplits: Terminal => Option[Splits],
                          ): Map[Queue, Map[MillisSinceEpoch, List[Double]]] =
    flight.bestSplits.orElse(terminalSplits(flight.apiFlight.Terminal)) match {
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
    val paxDistribution = distributePaxOverSplitsAndMinutes(
      passengerCount = totalPax,
      passengersPerMinute = 20,
      paxSplits = wholeSplits.map(s => (s.paxTypeAndQueue, s.paxCount.toInt)).toMap,
      firstMinute = startMinute.millisSinceEpoch,
    )
    val relevantPaxDistribution = paxDistribution.view.filterKeys(m => windowStart <= m && m <= windowEnd).toMap
    val workloadDistribution = paxDistributionToWorkloads(relevantPaxDistribution, processingTime, queueStatus, queueFallbacks)
    transposeMaps(workloadDistribution)
  }

  def distributePaxOverSplitsAndMinutes(passengerCount: Int,
                                        passengersPerMinute: Int,
                                        paxSplits: Map[PaxTypeAndQueue, Int],
                                        firstMinute: MillisSinceEpoch,
                                       ): Map[MillisSinceEpoch, Map[PaxTypeAndQueue, Int]] = {
    val groups = (passengerCount.toDouble / passengersPerMinute).ceil.toInt
    val splitPcts = paxSplits.view.mapValues(s => s.toDouble / passengerCount).toMap
    val emptySplitCounts: Map[PaxTypeAndQueue, Int] = paxSplits.keys.map(k => k -> 0).toMap
    val groupToMinute: Int => MillisSinceEpoch = g => firstMinute + (g - 1) * MilliTimes.oneMinuteMillis
    val emptyMinutesOfPax: Map[MillisSinceEpoch, Map[PaxTypeAndQueue, Int]] = (1 to groups).map(g => groupToMinute(g) -> emptySplitCounts).toMap
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
        val newPaxForGroup = splitPcts.map { case (split, pct) =>
          val paxForSplit = (pctSoFar * pct * passengerCount).round.toInt
          val pax = if (paxForSplit >= alreadyDistributed(split)) paxForSplit - alreadyDistributed(split) else 0
          (split, pax)
        }

        val desiredGroupSize = if (group == groups)
          passengerCount - alreadyDistributed.values.sum
        else
          passengersPerMinute

        val finalGroup = newPaxForGroup.values.sum match {
          case lowerGroupSize if lowerGroupSize < desiredGroupSize =>
            increaseToDesiredSize(newPaxForGroup, passengersPerMinute, paxSplits, alreadyDistributed)
          case lowerGroupSize if lowerGroupSize > desiredGroupSize =>
            decreaseToDesiredSize(newPaxForGroup, passengersPerMinute, paxSplits, alreadyDistributed)
          case _ => newPaxForGroup
        }

        acc + (groupToMinute(group) -> finalGroup)
    }
  }

  def utilisation(splits: Map[PaxTypeAndQueue, Int],
                  splitsTotal: Map[PaxTypeAndQueue, Int],
                  splitsSoFar: Map[PaxTypeAndQueue, Int],
                 ): Seq[(PaxTypeAndQueue, Double)] =
    splitsTotal.toSeq
      .map {
        case (k, v) =>
          val alreadyDistributed = splits.getOrElse(k, 0) + splitsSoFar.getOrElse(k, 0)
          k -> alreadyDistributed.toDouble / v.toDouble
      }

  def increaseToDesiredSize(splitsGroup: Map[PaxTypeAndQueue, Int],
                            desiredGroupSize: Int,
                            splitsTotal: Map[PaxTypeAndQueue, Int],
                            splitsSoFar: Map[PaxTypeAndQueue, Int],
                           ): Map[PaxTypeAndQueue, Int] = {
    val groupSize = desiredGroupSize
    val toAdd = groupSize - splitsGroup.values.sum

    (1 to toAdd).foldLeft(splitsGroup) {
      case (splits, _) =>
        val lowestUtilisedSplit = lowestUtilised(splitsTotal, splitsSoFar, splits)
        splits + (lowestUtilisedSplit -> (splits.getOrElse(lowestUtilisedSplit, 0) + 1))
    }
  }

  private def lowestUtilised(splitsTotal: Map[PaxTypeAndQueue, Int],
                             splitsSoFar: Map[PaxTypeAndQueue, Int],
                             splits: Map[PaxTypeAndQueue, Int],
                            ): PaxTypeAndQueue =
    utilisation(splits, splitsTotal, splitsSoFar)
      .sortBy(_._2).map(_._1)
      .headOption.getOrElse(throw new Exception("No splits found"))

  def decreaseToDesiredSize(splitsGroup: Map[PaxTypeAndQueue, Int],
                            desiredGroupSize: Int,
                            splitsTotal: Map[PaxTypeAndQueue, Int],
                            splitsSoFar: Map[PaxTypeAndQueue, Int],
                           ): Map[PaxTypeAndQueue, Int] = {
    val groupSize = desiredGroupSize
    val toRemove = splitsGroup.values.sum - groupSize

    (1 to toRemove).foldLeft(splitsGroup) {
      case (splits, _) =>
        val highestUtilisedSplit = highestAlreadyUtilisedNonZero(splitsTotal, splitsSoFar, splits)
        splits + (highestUtilisedSplit -> (splits.getOrElse(highestUtilisedSplit, 0) - 1))
    }
  }

  def highestAlreadyUtilisedNonZero(splitsTotal: Map[PaxTypeAndQueue, Int],
                                    splitsSoFar: Map[PaxTypeAndQueue, Int],
                                    splits: Map[PaxTypeAndQueue, Int],
                                   ): PaxTypeAndQueue =
    utilisation(splits, splitsTotal, splitsSoFar)
      .filter { case (s, _) => splits(s) > 0 }
      .sortBy(_._2).reverse.map(_._1)
      .headOption.getOrElse(throw new Exception("No splits found"))

  private def paxDistributionToWorkloads(distribution: Map[MillisSinceEpoch, Map[PaxTypeAndQueue, Int]],
                                         processingTime: (PaxType, Queue) => Double,
                                         queueStatus: (Queue, MillisSinceEpoch) => QueueStatus,
                                         queueFallbacks: (Queue, PaxType) => List[Queue],
                                        ): Map[MillisSinceEpoch, Map[Queue, List[Double]]] =
    distribution.map { case (millisecondMinute, splitCounts) =>
      val workloads = splitCounts.foldLeft(Map[Queue, List[Double]]()) {
        case (acc, (PaxTypeAndQueue(paxType, queue), count)) =>
          val finalQueue = if (queueStatus(queue, millisecondMinute) == Open)
            queue
          else
            queueFallbacks(queue, paxType).find(queueStatus(_, millisecondMinute) == Open).getOrElse {
              log.error(s"No fallback for closed queue $queue at ${SDate(millisecondMinute).toISOString}. Resorting to closed queue")
              queue
            }
          val time = processingTime(paxType, finalQueue)
          val existingQueueLoad = acc.getOrElse(finalQueue, List.empty[Double])
          val newQueueLoad = existingQueueLoad ++ List.fill(count)(time)
          acc + (finalQueue -> newQueueLoad)
      }
      (millisecondMinute, workloads)
    }

  private def transposeMaps(workload: Map[MillisSinceEpoch, Map[Queue, List[Double]]]): Map[Queue, Map[MillisSinceEpoch, List[Double]]] =
    workload.foldLeft(Map[Queue, Map[MillisSinceEpoch, List[Double]]]()) { case (acc, (millis, queueWorkloads)) =>
      queueWorkloads.foldLeft(acc) { case (acc, (queue, workloads)) =>
        val existingQueueWorkloads = acc.getOrElse(queue, Map[MillisSinceEpoch, List[Double]]())
        val newQueueWorkloads = existingQueueWorkloads + (millis -> workloads)
        acc + (queue -> newQueueWorkloads)
      }
    }

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
