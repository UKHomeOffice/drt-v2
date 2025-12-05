package services.graphstages

import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.stream.Materializer
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.WholePassengerQueueSplits
import services.graphstages.Crunch.SplitMinutes
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PaxType, PaxTypeAndQueue}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.ExecutionContext


trait WorkloadCalculatorLike {
  val terminalProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]

  def flightLoadMinutes(minuteMillis: NumericRange[MillisSinceEpoch],
                        flights: FlightsWithSplits,
                        redListUpdates: RedListUpdates,
                        terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus,
                        paxFeedSourceOrder: List[FeedSource],
                        terminalSplits: Terminal => Option[Splits],
                       )
                       (implicit ex: ExecutionContext, mat: Materializer): SplitMinutes

  val flightHasWorkload: FlightFilter

  def flightsWithPcpWorkload(flights: Iterable[ApiFlightWithSplits], redListUpdates: RedListUpdates): Iterable[ApiFlightWithSplits] =
    flights.filter(fws => terminalProcTimes.contains(fws.apiFlight.Terminal) && flightHasWorkload.apply(fws, redListUpdates))

}

case class DynamicWorkloadCalculator(terminalProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     fallbacks: QueueFallbacks,
                                     flightHasWorkload: FlightFilter,
                                     fallbackProcessingTime: Double,
                                     uniqueFlights: Seq[ApiFlightWithSplits] => Iterable[ApiFlightWithSplits],
                                    )
  extends WorkloadCalculatorLike {

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def flightLoadMinutes(minuteMillis: NumericRange[MillisSinceEpoch],
                                 flights: FlightsWithSplits,
                                 redListUpdates: RedListUpdates,
                                 terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus,
                                 paxFeedSourceOrder: List[FeedSource],
                                 terminalSplits: Terminal => Option[Splits],
                                )
                                (implicit ex: ExecutionContext, mat: Materializer): SplitMinutes = {
    val uniqueWithCodeShares = uniqueFlights(flights.flights.values.toSeq)
    val relevantFlights = flightsWithPcpWorkload(uniqueWithCodeShares, redListUpdates)
    val procTimes = (terminal: Terminal) => (paxType: PaxType, queue: Queue) =>
      terminalProcTimes
        .getOrElse(terminal, Map.empty)
        .getOrElse(PaxTypeAndQueue(paxType, queue), fallbackProcessingTime)

    val loadMinutes = relevantFlights
      .groupBy(_.apiFlight.Terminal)
      .flatMap { case (terminal, flightsForTerminal) =>
        val distributePaxOverQueuesAndMinutes = WholePassengerQueueSplits.wholePaxLoadsPerQueuePerMinute(minuteMillis, procTimes(terminal), terminalQueueStatuses(terminal))
        val workloadForFlight = WholePassengerQueueSplits.paxWorkloadsByQueue(distributePaxOverQueuesAndMinutes, fallbacks, paxFeedSourceOrder, terminalSplits)
        WholePassengerQueueSplits.queueWorkloads(workloadForFlight, flightsForTerminal)
      }

    SplitMinutes(loadMinutes)
  }
}
