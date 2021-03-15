package services.graphstages

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes.{B5JPlusNational, B5JPlusNationalBelowEGateAge, EeaBelowEGateAge, EeaMachineReadable, EeaNonMachineReadable, NonVisaNational, VisaNational}
import drt.shared.Queues.{Closed, EGate, EeaDesk, NonEeaDesk, Open, Queue, QueueStatus}
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.{FlightSplitMinute, SplitMinutes}
import services.workloadcalculator.PaxLoadCalculator.Load

import scala.collection.immutable.Map

trait WorkloadCalculatorLike {
  val queueStatusAt: (Terminal, Queue, MillisSinceEpoch) => Queues.QueueStatus

  val defaultProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]

  def flightLoadMinutes(flights: FlightsWithSplits): SplitMinutes

  def combineCodeShares(flights: Iterable[ApiFlightWithSplits]): Iterable[ApiFlightWithSplits] = {
    val uniqueFlights: Iterable[ApiFlightWithSplits] = flights
      .toList
      .sortBy(_.apiFlight.ActPax.getOrElse(0))
      .map { fws => (CodeShareKeyOrderedBySchedule(fws), fws) }
      .toMap.values
    uniqueFlights
  }

  def flightsWithPcpWorkload(flights: Iterable[ApiFlightWithSplits]): Iterable[ApiFlightWithSplits] = flights
    .filter { fws =>
      !fws.apiFlight.isCancelled &&
        defaultProcTimes.contains(fws.apiFlight.Terminal) &&
        !fws.apiFlight.Origin.isCta
    }

  def paxTypeAndQueueCountsFromSplits(splitsToUse: Splits): Set[ApiPaxTypeAndQueueCount] = {
    val splitRatios: Set[ApiPaxTypeAndQueueCount] = splitsToUse.splitStyle match {
      case UndefinedSplitStyle => splitsToUse.splits.map(qc => qc.copy(paxCount = 0))
      case PaxNumbers =>
        val splitsWithoutTransit = splitsToUse.splits.filter(_.queueType != Queues.Transfer)
        val totalSplitsPax: Load = splitsWithoutTransit.toList.map(_.paxCount).sum
        if (totalSplitsPax == 0.0)
          splitsWithoutTransit
        else
          splitsWithoutTransit.map(qc => qc.copy(paxCount = qc.paxCount / totalSplitsPax))
      case _ => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / 100))
    }
    splitRatios
  }

}


case class DynamicWorkloadCalculator(defaultProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     queueStatusAt: (Terminal, Queue, MillisSinceEpoch) => Queues.QueueStatus)
  extends WorkloadCalculatorLike {

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def flightLoadMinutes(flights: FlightsWithSplits): SplitMinutes = {
    val minutes = new SplitMinutes

    flightsWithPcpWorkload(combineCodeShares(flights.flights.values))
      .foreach(incoming => minutes ++= flightToFlightSplitMinutes(incoming))

    minutes
  }

  def flightToFlightSplitMinutes(flightWithSplits: ApiFlightWithSplits): Iterable[FlightSplitMinute] =
    defaultProcTimes.get(flightWithSplits.apiFlight.Terminal) match {
      case None => Iterable()
      case Some(procTimes) =>
        val flight = flightWithSplits.apiFlight

        flightWithSplits.bestSplits.map(splitsToUse => {
          val paxTypeAndQueueCounts = paxTypeAndQueueCountsFromSplits(splitsToUse)

          val paxTypesAndQueuesMinusTransit = paxTypeAndQueueCounts.filterNot(_.queueType == Queues.Transfer)

          flight.paxDeparturesByMinute(20)
            .flatMap {
              case (minuteMillis, flightPaxInMinute) =>
                paxTypesAndQueuesMinusTransit
                  .map { case ptqc@ApiPaxTypeAndQueueCount(pt, queue, _, _, _) =>
                    def findAlternativeQueue(originalQueue: Queue, queuesToTry: Iterable[Queue]): Queue = {
                      queuesToTry.find(queueStatusAt(flight.Terminal, _, minuteMillis) == Open) match {
                        case Some(queue) => queue
                        case None =>
                          log.error(s"Failed to find alternative queue for $pt. Reverting to $originalQueue")
                          originalQueue
                      }
                    }

                    val finalPtqc = queueStatusAt(flight.Terminal, queue, minuteMillis) match {
                      case Closed =>
                        val newQueue = (queue, pt) match {
                          case (EGate, EeaMachineReadable | EeaNonMachineReadable | EeaBelowEGateAge) =>
                            findAlternativeQueue(EGate, Iterable(EeaDesk, NonEeaDesk))
                          case (EGate, VisaNational | NonVisaNational | B5JPlusNational | B5JPlusNationalBelowEGateAge) =>
                            findAlternativeQueue(EGate, Iterable(NonEeaDesk, EeaDesk))
                        }
                        ptqc.copy(queueType = newQueue)
                      case Open =>
                        ptqc
                    }

                    flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, finalPtqc)
                  }
            }
        }).getOrElse(Seq())
    }


  def flightSplitMinute(arrival: Arrival,
                        procTimes: Map[PaxTypeAndQueue, Load],
                        minuteMillis: MillisSinceEpoch,
                        flightPaxInMinute: Int,
                        apiSplitRatio: ApiPaxTypeAndQueueCount
                       ): FlightSplitMinute = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val paxTypeQueueProcTime = procTimes.getOrElse(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType), 0d)
    val defaultWorkload = splitPaxInMinute * paxTypeQueueProcTime

    FlightSplitMinute(CodeShareKeyOrderedBySchedule(arrival), apiSplitRatio.passengerType, arrival.Terminal, apiSplitRatio.queueType, splitPaxInMinute, defaultWorkload, minuteMillis)
  }
}
