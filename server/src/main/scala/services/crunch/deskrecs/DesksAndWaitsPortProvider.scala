package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{Queue, Transfer}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{AirportConfig, PaxTypeAndQueue, TQM}
import org.slf4j.{Logger, LoggerFactory}
import services.TryCrunch
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs
import services.graphstages.Crunch.LoadMinute
import services.graphstages.WorkloadCalculator

import scala.collection.immutable.{Map, NumericRange, SortedMap}

case class DesksAndWaitsPortProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                     divertedQueues: Map[Queue, Queue],
                                     desksByTerminal: Map[Terminal, Int],
                                     flexedQueuesPriority: List[Queue],
                                     slas: Map[Queue, Int],
                                     terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     minutesToCrunch: Int,
                                     crunchOffsetMinutes: Int,
                                     eGateBankSize: Int,
                                     tryCrunch: TryCrunch,
                                     pcpPaxFn: Arrival => Int
                                    ) extends DesksAndWaitsPortProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                                  loadsByQueue: Map[TQM, LoadMinute],
                                  deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike]): Map[TQM, SimulationMinute] = {
    val deskRecMinutes = loadsToDesks(minuteMillis, loadsByQueue, deskLimitProviders)
    deskRecsToSimulations(deskRecMinutes.minutes)
  }

  def deskRecsToSimulations(terminalQueueDeskRecs: Seq[DeskRecMinute]): Map[TQM, SimulationMinute] = terminalQueueDeskRecs
    .map {
      case DeskRecMinute(t, q, m, _, _, d, w) => (TQM(t, q, m), SimulationMinute(t, q, m, d, w))
    }.toMap

  def terminalDescRecs(terminal: Terminal): DesksAndWaitsTerminalProvider =
    deskrecs.DesksAndWaitsTerminalProvider(slas, flexedQueuesPriority, tryCrunch, eGateBankSize)

  def queueLoadsFromFlights(flights: FlightsWithSplits): Map[TQM, LoadMinute] = WorkloadCalculator
    .flightLoadMinutes(flights, terminalProcessingTimes, pcpPaxFn).minutes
    .groupBy {
      case (TQM(t, q, m), _) => val finalQueueName = divertedQueues.getOrElse(q, q)
        TQM(t, finalQueueName, m)
    }
    .map {
      case (tqm, minutes) =>
        val loads = minutes.values
        (tqm, LoadMinute(tqm.terminal, tqm.queue, loads.map(_.paxLoad).sum, loads.map(_.workLoad).sum, tqm.minute))
    }

  def terminalWorkLoadsByQueue(terminal: Terminal,
                               minuteMillis: NumericRange[MillisSinceEpoch],
                               loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = queuesByTerminal(terminal)
    .filterNot(_ == Transfer)
    .map { queue =>
      val lms = minuteMillis.map(minute => loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)).workLoad)
      (queue, lms)
    }
    .toMap

  def terminalPaxLoadsByQueue(terminal: Terminal, minuteMillis: NumericRange[MillisSinceEpoch],
                              loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = queuesByTerminal(terminal)
    .filterNot(_ == Transfer)
    .map { queue =>
      val paxLoads = minuteMillis.map(minute => loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)).paxLoad)
      (queue, paxLoads)
    }
    .toMap

  override def flightsToLoads(flights: FlightsWithSplits,
                              crunchStartMillis: MillisSinceEpoch): Map[TQM, LoadMinute] = queueLoadsFromFlights(flights)

  override def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                            loads: Map[TQM, LoadMinute],
                            maxDesksByTerminal: Map[Terminal, TerminalDeskLimitsLike]): DeskRecMinutes = {
    val terminalQueueDeskRecs = maxDesksByTerminal.map {
      case (terminal, maxDesksProvider) =>
        val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loads)
        val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loads)
        log.info(s"Optimising $terminal")

        terminalDescRecs(terminal).workToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, maxDesksProvider)
    }

    DeskRecMinutes(terminalQueueDeskRecs.toSeq.flatten)
  }
}

object DesksAndWaitsPortProvider {
  def apply(airportConfig: AirportConfig, tryCrunch: TryCrunch, pcpPaxFn: Arrival => Int): DesksAndWaitsPortProvider =
    DesksAndWaitsPortProvider(
      airportConfig.queuesByTerminal,
      airportConfig.divertedQueues,
      airportConfig.desksByTerminal,
      airportConfig.queuePriority,
      airportConfig.slaByQueue,
      airportConfig.terminalProcessingTimes,
      airportConfig.minutesToCrunch,
      airportConfig.crunchOffsetMinutes,
      airportConfig.eGateBankSize,
      tryCrunch,
      pcpPaxFn
    )
}
