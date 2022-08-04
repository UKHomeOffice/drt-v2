package passengersplits

import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxType}
import uk.gov.homeoffice.drt.ports.Queues.{Queue, Transfer}

object WholePassengerQueueSplits {
  def wholePaxPerQueuePerMinute(totalPax: Int,
                                        wholeSplits: Set[ApiPaxTypeAndQueueCount],
                                        processingTime: (PaxType, Queue) => Double): Map[Queue, Map[Int, List[Double]]] =
    wholeSplits
      .toList
      .map { ptqc =>
        val procTime = processingTime(ptqc.passengerType, ptqc.queueType)
        val paxPerMinute = paxLoadsPerMinute(totalPax, ptqc.paxCount.toInt, 20, procTime)
        (ptqc.queueType, paxPerMinute)
      }
      .flatMap {
        case (queue, loadsByMinute) => loadsByMinute.map {
          case (minute, loads) => (queue, minute, loads)
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

  def wholePassengerSplits(totalPax: Int, splits: Set[ApiPaxTypeAndQueueCount]): Set[ApiPaxTypeAndQueueCount] = {
    val splitsMinusTransferInOrder = splits.toList
      .sortBy(_.paxCount)
      .filterNot(_.queueType == Transfer)
    val totalSplitsPax = splits.toList.map(_.paxCount).sum
    val totalSplits = splitsMinusTransferInOrder.size

    splitsMinusTransferInOrder.foldLeft(Set[ApiPaxTypeAndQueueCount]()) {
      case (actualSplits, nextSplit) =>
        val countSoFar = actualSplits.map(_.paxCount).sum
        val proposedCount = Math.round((nextSplit.paxCount / totalSplitsPax) * totalPax).toInt

        val finalSplit = actualSplits.size == totalSplits - 1
        val actualCount = if (proposedCount + countSoFar <= totalPax && !finalSplit)
          proposedCount
        else totalPax - countSoFar

        actualSplits + nextSplit.copy(paxCount = actualCount)
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
