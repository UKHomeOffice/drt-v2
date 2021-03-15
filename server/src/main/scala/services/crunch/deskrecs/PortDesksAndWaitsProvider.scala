package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{Closed, Open, Queue, Transfer}
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs
import services.graphstages.Crunch.{LoadMinute, europeLondonTimeZone}
import services.graphstages.{DynamicWorkloadCalculator, WorkloadCalculatorLike}
import services.{SDate, TryCrunch}

import scala.collection.immutable.{Map, NumericRange, SortedMap}

case class PortDesksAndWaitsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                     divertedQueues: Map[Queue, Queue],
                                     desksByTerminal: Map[Terminal, Int],
                                     flexedQueuesPriority: List[Queue],
                                     slas: Map[Queue, Int],
                                     terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     minutesToCrunch: Int,
                                     crunchOffsetMinutes: Int,
                                     eGateBankSize: Int,
                                     tryCrunch: TryCrunch,
                                     workloadCalculator: WorkloadCalculatorLike
                                    ) extends PortDesksAndWaitsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                                  loadsByQueue: Map[TQM, LoadMinute],
                                  deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike]): SimulationMinutes = {
    val deskRecMinutes = loadsToDesks(minuteMillis, loadsByQueue, deskLimitProviders)
    SimulationMinutes(deskRecsToSimulations(deskRecMinutes.minutes).values)
  }

  def deskRecsToSimulations(terminalQueueDeskRecs: Seq[DeskRecMinute]): Map[TQM, SimulationMinute] = terminalQueueDeskRecs
    .map {
      case DeskRecMinute(t, q, m, _, _, d, w) => (TQM(t, q, m), SimulationMinute(t, q, m, d, w))
    }.toMap

  def terminalDescRecs(terminal: Terminal): TerminalDesksAndWaitsProvider =
    deskrecs.TerminalDesksAndWaitsProvider(slas, flexedQueuesPriority, tryCrunch, eGateBankSize)

  override def flightsToLoads(flights: FlightsWithSplits): Map[TQM, LoadMinute] = workloadCalculator
    .flightLoadMinutes(flights).minutes
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

  override def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                            loads: Map[TQM, LoadMinute],
                            deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike]): DeskRecMinutes = {
    val terminalQueueDeskRecs = deskLimitProviders.map {
      case (terminal, maxDesksProvider) =>
        val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loads)
        val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loads)
        log.debug(s"Optimising $terminal")

        terminalDescRecs(terminal).workToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, maxDesksProvider)
    }

    DeskRecMinutes(terminalQueueDeskRecs.toSeq.flatten)
  }
}

object PortDesksAndWaitsProvider {
  def apply(airportConfig: AirportConfig, tryCrunch: TryCrunch): PortDesksAndWaitsProvider = {
    val calculator = DynamicWorkloadCalculator(
      airportConfig.terminalProcessingTimes,
      airportConfig.queueStatusProvider(millis => SDate(millis, europeLondonTimeZone)))

    PortDesksAndWaitsProvider(
      queuesByTerminal = airportConfig.queuesByTerminal,
      divertedQueues = airportConfig.divertedQueues,
      desksByTerminal = airportConfig.desksByTerminal,
      flexedQueuesPriority = airportConfig.queuePriority,
      slas = airportConfig.slaByQueue,
      terminalProcessingTimes = airportConfig.terminalProcessingTimes,
      minutesToCrunch = airportConfig.minutesToCrunch,
      crunchOffsetMinutes = airportConfig.crunchOffsetMinutes,
      eGateBankSize = airportConfig.eGateBankSize,
      tryCrunch = tryCrunch,
      workloadCalculator = calculator
    )
  }
}
