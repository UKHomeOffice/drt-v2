package services.graphstages

import akka.stream.Materializer
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.WholePassengerQueueSplits
import services.graphstages.Crunch.SplitMinutes
import services.workloadcalculator.PaxLoadCalculator.Load
import uk.gov.homeoffice.drt.arrivals.SplitStyle.{PaxNumbers, UndefinedSplitStyle}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxType, PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.ExecutionContext


trait WorkloadCalculatorLike {
  val terminalProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]

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

case class DynamicWorkloadCalculator(terminalProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     fallbacksProvider: QueueFallbacks,
                                     flightHasWorkload: FlightFilter,
                                     fallbackProcessingTime: Double)
  extends WorkloadCalculatorLike {

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def flightLoadMinutes(minuteMillis: NumericRange[MillisSinceEpoch],
                                 flights: FlightsWithSplits,
                                 redListUpdates: RedListUpdates,
                                 terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus)
                                (implicit ex: ExecutionContext, mat: Materializer): SplitMinutes = {
    val relevantFlights = flightsWithPcpWorkload(combineCodeShares(flights.flights.values), redListUpdates)
    val procTimes = (terminal: Terminal) => (paxType: PaxType, queue: Queue) =>
      terminalProcTimes
        .getOrElse(terminal, Map.empty)
        .getOrElse(PaxTypeAndQueue(paxType, queue), fallbackProcessingTime)

    SplitMinutes(WholePassengerQueueSplits.splits(relevantFlights, procTimes))
  }
}
