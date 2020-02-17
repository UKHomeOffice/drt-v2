package services.crunch.deskrecs.flexed

import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, PaxTypeAndQueue, TQM}
import org.slf4j.{Logger, LoggerFactory}
import services.TryCrunch
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.{ProductionPortDeskRecsProviderLike, TerminalDeskRecsProviderLike}
import services.graphstages.Crunch.LoadMinute
import services.graphstages.SimulationMinute

import scala.collection.immutable.{Map, NumericRange, SortedMap}

case class FlexedPortDeploymentsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                         divertedQueues: Map[Queue, Queue],
                                         desksByTerminal: Map[Terminal, Int],
                                         flexedQueuesPriority: List[Queue],
                                         slas: Map[Queue, Int],
                                         terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                         minutesToCrunch: Int,
                                         crunchOffsetMinutes: Int,
                                         eGateBankSize: Int,
                                         tryCrunch: TryCrunch) extends ProductionPortDeskRecsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def loadsToDeployments(minuteMillis: NumericRange[MillisSinceEpoch],
                         loads: Map[TQM, LoadMinute],
                         desksProviderByTerminal: Map[Terminal, TerminalDeskLimitsLike]): Map[TQM, SimulationMinute] = {
    val deskRecs = desksProviderByTerminal
      .map { case (terminal, maxDesksProvider) =>
        val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loads)
        val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loads)
        log.info(s"Optimising $terminal")

        terminalDescRecs(terminal).workToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, maxDesksProvider)
      }
      .toSeq.flatten

    deskRecsToSimulations(deskRecs)
  }

  def deskRecsToSimulations(terminalQueueDeskRecs: Seq[DeskRecMinute]): Map[TQM, SimulationMinute] = terminalQueueDeskRecs.map {
    case DeskRecMinute(t, q, m, _, _, d, w) => (TQM(t, q, m), SimulationMinute(t, q, m, d, w))
  }.toMap

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsProviderLike =
    FlexedTerminalDeskRecsProvider(slas, flexedQueuesPriority, tryCrunch, eGateBankSize)
}

object FlexedPortDeploymentsProvider {
  def apply(airportConfig: AirportConfig, minutesToCrunch: Int, tryCrunch: TryCrunch): FlexedPortDeploymentsProvider =
    FlexedPortDeploymentsProvider(airportConfig.queuesByTerminal,
                                  airportConfig.divertedQueues,
                                  airportConfig.desksByTerminal,
                                  airportConfig.flexedQueuesPriority,
                                  airportConfig.slaByQueue,
                                  airportConfig.terminalProcessingTimes,
                                  minutesToCrunch,
                                  airportConfig.crunchOffsetMinutes,
                                  airportConfig.eGateBankSize,
                                  tryCrunch)
}
