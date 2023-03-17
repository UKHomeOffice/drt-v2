package services.graphstages

import akka.stream.Materializer
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.WholePassengerQueueSplits
import services.graphstages.Crunch.SplitMinutes
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{PaxType, PaxTypeAndQueue}
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

    SplitMinutes(WholePassengerQueueSplits.splits(minuteMillis, relevantFlights, procTimes, terminalQueueStatuses, fallbacksProvider))
  }
}
