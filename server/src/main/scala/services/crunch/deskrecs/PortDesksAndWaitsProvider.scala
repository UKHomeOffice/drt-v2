package services.crunch.deskrecs

import akka.stream.Materializer
import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, MillisSinceEpoch, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.TryCrunchWholePax
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs
import services.graphstages.{DynamicWorkloadCalculator, FlightFilter, WorkloadCalculatorLike}
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.AirportConfigDefaults
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PaxTypeAndQueue}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.immutable
import scala.collection.immutable.{Map, NumericRange, SortedMap}
import scala.concurrent.{ExecutionContext, Future}

case class PortDesksAndWaitsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                     divertedQueues: Map[Queue, Queue],
                                     desksByTerminal: Map[Terminal, Int],
                                     flexedQueuesPriority: List[Queue],
                                     sla: (LocalDate, Queue) => Future[Int],
                                     terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     minutesToCrunch: Int,
                                     crunchOffsetMinutes: Int,
                                     tryCrunch: TryCrunchWholePax,
                                     workloadCalculator: WorkloadCalculatorLike,
                                     paxFeedSourceOrder: List[FeedSource],
                                    ) extends PortDesksAndWaitsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                                  passengersByQueue: Map[TQM, PassengersMinute],
                                  deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike],
                                  description: String)
                                 (implicit ec: ExecutionContext, mat: Materializer): Future[SimulationMinutes] = {
    loadsToDesks(minuteMillis, passengersByQueue, deskLimitProviders, description).map(deskRecMinutes =>
      SimulationMinutes(deskRecsToSimulations(deskRecMinutes.minutes).values.toSeq)
    )
  }

  private def deskRecsToSimulations(terminalQueueDeskRecs: Iterable[DeskRecMinute]): Map[TQM, SimulationMinute] = terminalQueueDeskRecs
    .map {
      case DeskRecMinute(t, q, m, _, _, d, w, _) => (TQM(t, q, m), SimulationMinute(t, q, m, d, w))
    }.toMap

  private def terminalDescRecs(terminal: Terminal, description: String): TerminalDesksAndWaitsProvider =
    deskrecs.TerminalDesksAndWaitsProvider(terminal, sla, flexedQueuesPriority, tryCrunch, description)

  override def flightsToLoads(minuteMillis: NumericRange[MillisSinceEpoch],
                              flights: FlightsWithSplits,
                              redListUpdates: RedListUpdates,
                              terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus)
                             (implicit ec: ExecutionContext, mat: Materializer): Map[TQM, PassengersMinute] = workloadCalculator
    .flightLoadMinutes(minuteMillis, flights, redListUpdates, terminalQueueStatuses, paxFeedSourceOrder).minutes
    .groupBy {
      case (TQM(t, q, m), _) => val finalQueueName = divertedQueues.getOrElse(q, q)
        TQM(t, finalQueueName, m)
    }
    .map {
      case (tqm, minutes) =>
        val loads = minutes.values
        (tqm, PassengersMinute(tqm.terminal, tqm.queue, tqm.minute, loads.flatMap(_.passengers), None) )
    }

  private def terminalWorkLoadsByQueue(terminal: Terminal,
                                       minuteMillis: NumericRange[MillisSinceEpoch],
                                       loadMinutes: Map[TQM, PassengersMinute]): Map[Queue, Seq[Double]] = queuesByTerminal(terminal)
    .filterNot(_ == Transfer)
    .map { queue =>
      val lms = minuteMillis.map(minute =>
        loadMinutes.getOrElse(TQM(terminal, queue, minute), PassengersMinute(terminal, queue, minute, Seq(), None)).passengers.sum)
      (queue, lms)
    }
    .toMap

  private def terminalPassengersByQueue(terminal: Terminal, minuteMillis: NumericRange[MillisSinceEpoch],
                                        loadMinutes: Map[TQM, PassengersMinute]): Map[Queue, immutable.IndexedSeq[Iterable[Double]]] = queuesByTerminal(terminal)
    .filterNot(_ == Transfer)
    .map { queue =>
      val paxLoads = minuteMillis.map { minute =>
        loadMinutes.get(TQM(terminal, queue, minute)) match {
          case Some(lm) => lm.passengers
          case None => Seq.empty[Double]
        }
      }
      (queue, paxLoads)
    }
    .toMap

  override def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                            loads: Map[TQM, PassengersMinute],
                            deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike],
                            description: String,
                           )
                           (implicit ec: ExecutionContext, mat: Materializer): Future[DeskRecMinutes] = {
    val terminalQueueDeskRecs = deskLimitProviders.map {
      case (terminal, maxDesksProvider) =>
        val terminalPassengers = terminalPassengersByQueue(terminal, minuteMillis, loads)
        val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loads)
        terminalDescRecs(terminal, description)
          .workToDeskRecs(terminal, minuteMillis, terminalPassengers, terminalWork, maxDesksProvider)
          .map(_.toList)
    }.toList

    Future.sequence(terminalQueueDeskRecs).map { deskRecs =>
      DeskRecMinutes(deskRecs.toSeq.flatten)
    }
  }
}

object PortDesksAndWaitsProvider {
  def apply(airportConfig: AirportConfig,
            tryCrunch: TryCrunchWholePax,
            flightFilter: FlightFilter,
            paxFeedSourceOrder: List[FeedSource],
            sla: (LocalDate, Queue) => Future[Int],
           ): PortDesksAndWaitsProvider = {

    val calculator = DynamicWorkloadCalculator(
      airportConfig.terminalProcessingTimes,
      QueueFallbacks(airportConfig.queuesByTerminal),
      flightFilter,
      AirportConfigDefaults.fallbackProcessingTime,
      paxFeedSourceOrder
    )

    PortDesksAndWaitsProvider(
      queuesByTerminal = airportConfig.queuesByTerminal,
      divertedQueues = airportConfig.divertedQueues,
      desksByTerminal = airportConfig.desksByTerminal,
      flexedQueuesPriority = airportConfig.queuePriority,
      sla = sla,
      terminalProcessingTimes = airportConfig.terminalProcessingTimes,
      minutesToCrunch = airportConfig.minutesToCrunch,
      crunchOffsetMinutes = airportConfig.crunchOffsetMinutes,
      tryCrunch = tryCrunch,
      workloadCalculator = calculator,
      paxFeedSourceOrder = paxFeedSourceOrder,
    )
  }
}
