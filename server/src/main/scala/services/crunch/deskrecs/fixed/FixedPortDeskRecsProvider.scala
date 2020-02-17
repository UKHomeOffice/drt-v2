package services.crunch.deskrecs.fixed

import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, PaxTypeAndQueue}
import org.slf4j.{Logger, LoggerFactory}
import services.TryCrunch
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.deskrecs.{ProductionPortDeskRecsProviderLike, TerminalDeskRecsProviderLike}

import scala.collection.immutable.{Map, SortedMap}

case class FixedPortDeskRecsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                     divertedQueues: Map[Queue, Queue],
                                     minDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                     maxDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                     slas: Map[Queue, Int],
                                     terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     minutesToCrunch: Int,
                                     crunchOffsetMinutes: Int,
                                     eGateBankSize: Int,
                                     tryCrunch: TryCrunch) extends ProductionPortDeskRecsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsProviderLike =
    FixedTerminalDeskRecsProvider(slas, tryCrunch, eGateBankSize)

  def maxDesksByTerminal(terminals: Set[Terminal]): Map[Terminal, TerminalDeskLimitsLike] = terminals
    .map { terminal => (terminal, FixedTerminalDeskLimits(minDesksByTerminalAndQueue24Hrs(terminal), maxDesksByTerminalAndQueue24Hrs(terminal))) }
    .toMap
}

object FixedPortDeskRecsProvider {
  def apply(airportConfig: AirportConfig, tryCrunch: TryCrunch): FixedPortDeskRecsProvider =
    FixedPortDeskRecsProvider(airportConfig.queuesByTerminal,
                              airportConfig.divertedQueues,
                              airportConfig.minDesksByTerminalAndQueue24Hrs,
                              airportConfig.maxDesksByTerminalAndQueue24Hrs,
                              airportConfig.slaByQueue,
                              airportConfig.terminalProcessingTimes,
                              airportConfig.minutesToCrunch,
                              airportConfig.crunchOffsetMinutes,
                              airportConfig.eGateBankSize,
                              tryCrunch)
}
