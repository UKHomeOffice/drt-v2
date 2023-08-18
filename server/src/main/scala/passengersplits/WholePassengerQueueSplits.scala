package passengersplits

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.TQM
import org.slf4j.LoggerFactory
import services.graphstages.Crunch.LoadMinute
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, FeedSource, PaxType}
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
          val x: Iterable[(TQM, List[Double])] = flights
            .flatMap { flight =>
              val s = flightSplits(minuteMillis, flight, procTimes, queueStatus(flight.apiFlight.Terminal), queueFallbacks, paxFeedSourceOrder)
                .flatMap { case (queue, byMinute) =>
                  byMinute.map {
                    case (minute, passengers) =>
                      (TQM(terminal, queue, minute), passengers)
                  }
                }
              val sTotal = s.map(_._2.size).sum
              val fTotal = flight.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)
              if (sTotal != fTotal) {
                log.error(s"Got a flight load difference! $sTotal != $fTotal")
              }
              s
            }
          val sTotal = x.map(_._2.size).sum
          val fTotal = flights.map(_.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)).sum
          if (sTotal != fTotal) {
            log.error(s"Got a $terminal load difference! $sTotal != $fTotal")
          }
          x
      }
      .groupBy { case (tqm, _) => tqm }
      .map {
        case (tqm, passengers) =>
          val loadsByPassenger = passengers.flatMap { case (_, paxLoads) => paxLoads}
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
        val x = wholePaxLoadsPerQueuePerMinute(minuteMillis, pcpPax, wholePaxSplits, processingTime, queueStatus, terminalQueueFallbacks, startMinute)
        val splitPaxTotal = x.values.map(_.values.map(_.size).sum).sum
        if (splitPaxTotal != pcpPax) {
          log.error(s"Got a queue load difference! ${flight.apiFlight.Terminal} ${flight.apiFlight.flightCode.toString()} ${startMinute.toISOString} - $splitPaxTotal != $pcpPax\n\nsplits were ${splitsToUse.source} / ${splitsToUse.splitStyle}:\n${wholePaxSplits}")
        }
        x
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
    wholeSplits
      .toList
      .flatMap { ptqc =>
        val procTime = processingTime(ptqc.passengerType, ptqc.queueType)
        val perMin = paxLoadsPerMinute(totalPax, ptqc.paxCount.toInt, 20, procTime)
        if (perMin.values.map(_.size).sum != ptqc.paxCount.toInt) {
          log.error(s"Got a pax load difference! ${perMin.values.map(_.size).sum} != ${ptqc.paxCount.toInt}")
        }
        perMin
          .filter { case (minute, _) =>
            val m = startMinute.addMinutes(minute - 1).millisSinceEpoch
            windowStart <= m && m <= windowEnd
          }
          .map { case (minute, passengerLoads) =>
            val minuteMillis = startMinute.addMinutes(minute - 1).millisSinceEpoch
            if (queueStatus(ptqc.queueType, minuteMillis) != Open) {
              val fallbacks = queueFallbacks(ptqc.queueType, ptqc.passengerType)
              val redirectedQueue = maybeFallbackQueue(queueStatus, fallbacks, minuteMillis).getOrElse {
                log.error(s"No fallback for closed queue ${ptqc.queueType} at ${SDate(minuteMillis).toISOString}. Resorting to closed queue")
                ptqc.queueType
              }
              (redirectedQueue, minuteMillis, passengerLoads)
            }
            else (ptqc.queueType, minuteMillis, passengerLoads)
          }
      }
      .groupBy(_._1)
      .map {
        case (queue, loads) =>
          val loadsByMinute = loads
            .groupBy(_._2)
            .map {
              case (minute, loads) => (minute, loads.flatMap(_._3))
            }
          (queue, loadsByMinute)
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
    val minutesOff = totalPassengers.toDouble / paxOffRate
    val paxPerMinuteDecimal = queuePassengers / minutesOff

    val paxLoadsByMinute = (1 to minutesOff.toInt).foldLeft(Map[Int, List[Double]]()) {
      case (paxLoadsAcc, minute) =>
        val roundedDecimalPaxForMinute = paxPerMinuteDecimal * minute
        val paxThisMinute = Math.round(roundedDecimalPaxForMinute).toInt - paxCountFromLoads(paxLoadsAcc.values)
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
