package passengersplits

import drt.shared.TQM
import org.slf4j.LoggerFactory
import services.SDate
import services.graphstages.Crunch.LoadMinute
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.Queues.{Queue, Transfer}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxType}

import scala.collection.immutable

object WholePassengerQueueSplits {
  private val log = LoggerFactory.getLogger(getClass)

  def splits(flights: Iterable[ApiFlightWithSplits], processingTime: Terminal => (PaxType, Queue) => Double): Map[TQM, LoadMinute] =
    flights
      .groupBy(_.apiFlight.Terminal)
      .toList
      .flatMap {
        case (terminal, flights) =>
          val procTimes = processingTime(terminal)
          flights
            .flatMap { flight =>
              val pcpTime = SDate(flight.apiFlight.pcpRange.min)
              flightSplits(flight, procTimes)
                .flatMap { case (queue, byMinute) =>
                  byMinute.map {
                    case (minute, passengers) =>
                      (TQM(terminal, queue, pcpTime.addMinutes(minute - 1).millisSinceEpoch), passengers)
                  }
                }
            }
      }
      .groupBy(_._1)
      .map {
        case (tqm, passengers) =>
          val pax: immutable.Seq[Double] = passengers.flatMap(_._2)
          (tqm, LoadMinute(tqm.terminal, tqm.queue, pax, pax.sum, tqm.minute))
      }

  def flightSplits(flight: ApiFlightWithSplits, processingTime: (PaxType, Queue) => Double): Map[Queue, Map[Int, List[Double]]] =
    flight.bestSplits match {
      case Some(splitsToUse) =>
        val pcpPax = flight.apiFlight.bestPcpPaxEstimate.pax.getOrElse(0)
        wholePaxPerQueuePerMinute(pcpPax, wholePassengerSplits(pcpPax, splitsToUse.splits), processingTime)
      case None =>
        log.error(s"No splits found for ${flight.apiFlight.flightCode}")
        Map.empty
    }

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
        val countSoFar = actualSplits.toList.map(_.paxCount).sum
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
