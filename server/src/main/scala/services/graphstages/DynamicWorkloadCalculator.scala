package services.graphstages

import akka.stream.Materializer
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{FlightSplitMinute, SplitMinutes}
import services.workloadcalculator.PaxLoadCalculator.Load
import uk.gov.homeoffice.drt.arrivals.SplitStyle.{PaxNumbers, UndefinedSplitStyle}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, Splits}
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.ExecutionContext


trait WorkloadCalculatorLike {
  val defaultProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]

  def flightLoadMinutes(minuteMillis: NumericRange[MillisSinceEpoch],
                        flights: FlightsWithSplits,
                        redListUpdates: RedListUpdates,
                        terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus)
                       (implicit ex: ExecutionContext, mat: Materializer): SplitMinutes

  def combineCodeShares(flights: Iterable[ApiFlightWithSplits]): Iterable[ApiFlightWithSplits] = {
    val uniqueFlights: Iterable[ApiFlightWithSplits] = flights
      .toList
      .sortBy(_.apiFlight.ActPax.getOrElse(0))
      .map { fws => (CodeShareKeyOrderedBySchedule(fws), fws) }
      .toMap.values
    uniqueFlights
  }

  val flightHasWorkload: FlightFilter

  def flightsWithPcpWorkload(flights: Iterable[ApiFlightWithSplits], redListUpdates: RedListUpdates): Iterable[ApiFlightWithSplits] =
    flights.filter(fws => flightHasWorkload.apply(fws, redListUpdates))

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
                                     fallbacksProvider: QueueFallbacks,
                                     flightHasWorkload: FlightFilter)
  extends WorkloadCalculatorLike {

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def flightLoadMinutes(minuteMillis: NumericRange[MillisSinceEpoch],
                                 flights: FlightsWithSplits,
                                 redListUpdates: RedListUpdates,
                                 terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus)
                                (implicit ex: ExecutionContext, mat: Materializer): SplitMinutes =
    flightsWithPcpWorkload(combineCodeShares(flights.flights.values), redListUpdates).toList
      .foldLeft(SplitMinutes(Map())) {
        case (acc, fws) =>
          acc ++ flightToFlightSplitMinutes(minuteMillis, fws, terminalQueueStatuses(fws.apiFlight.Terminal))
      }

  def flightToFlightSplitMinutes(minuteMillis: NumericRange[MillisSinceEpoch],
                                 flightWithSplits: ApiFlightWithSplits,
                                 statusAt: (Queue, MillisSinceEpoch) => QueueStatus)
                                (implicit ec: ExecutionContext): Iterable[FlightSplitMinute] = {

    defaultProcTimes.get(flightWithSplits.apiFlight.Terminal) match {
      case None => Iterable()
      case Some(procTimes) =>
        val flight = flightWithSplits.apiFlight

        flightWithSplits.bestSplits.map { splitsToUse =>
          val paxTypeAndQueueCounts = paxTypeAndQueueCountsFromSplits(splitsToUse)

          val paxTypesAndQueuesMinusTransit = paxTypeAndQueueCounts.filterNot(_.queueType == Queues.Transfer)

          flight
            .paxDeparturesByMinute(20)
            .filter {
              case (minute, _) => minuteMillis.contains(minute)
            }
            .flatMap {
              case (minuteMillis, flightPaxInMinute) =>
                paxTypesAndQueuesMinusTransit
                  .map { case ptqc@ApiPaxTypeAndQueueCount(pt, queue, _, _, _) =>
                    def findAlternativeQueue(originalQueue: Queue, queuesToTry: Iterable[Queue]): Queue = {
                      queuesToTry.find(statusAt(_, minuteMillis) == Open) match {
                        case Some(queue) => queue
                        case None =>
                          log.error(s"Failed to find alternative queue (out of $queuesToTry) for $pt at ${SDate(minuteMillis).toISOString()}. Reverting to $originalQueue")
                          originalQueue
                      }
                    }

                    val finalPtqc = statusAt(queue, minuteMillis) match {
                      case Closed =>
                        val fallbacks = fallbacksProvider.availableFallbacks(flight.Terminal, queue, pt)
                        val newQueue = findAlternativeQueue(queue, fallbacks)
                        log.info(s"$queue is closed at ${SDate(minuteMillis).toISOString()}. Redirecting to $newQueue")
                        ptqc.copy(queueType = newQueue)
                      case Open => ptqc
                    }

                    flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, finalPtqc)
                  }
            }
        }.getOrElse {
          log.error(s"Missing splits for ${flight.flightCode}::${SDate(flight.Scheduled).toISOString()}")
          Seq()
        }
    }
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
