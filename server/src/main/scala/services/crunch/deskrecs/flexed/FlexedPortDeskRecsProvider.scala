package services.crunch.deskrecs.flexed

import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, PaxTypeAndQueue}
import org.slf4j.{Logger, LoggerFactory}
import services.TryCrunch
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimits
import services.crunch.deskrecs.{ProductionPortDeskRecsProviderLike, TerminalDeskRecsProviderLike}

import scala.collection.immutable.{Map, SortedMap}

case class FlexedPortDeskRecsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                      divertedQueues: Map[Queue, Queue],
                                      minDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                      maxDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                      desksByTerminal: Map[Terminal, Int],
                                      flexedQueuesPriority: List[Queue],
                                      slas: Map[Queue, Int],
                                      terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                      minutesToCrunch: Int,
                                      crunchOffsetMinutes: Int,
                                      eGateBankSize: Int,
                                      tryCrunch: TryCrunch) extends ProductionPortDeskRecsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsProviderLike =
    FlexedTerminalDeskRecsProvider(slas, flexedQueuesPriority, tryCrunch, eGateBankSize)

  def maxDesksByTerminal(terminals: Set[Terminal]): Map[Terminal, TerminalDeskLimitsLike] = terminals
    .map { terminal =>
      val desks = desksByTerminal(terminal)
      val terminalDesksByMinute = List.fill(minutesToCrunch)(desks)
      val minDesksByQueue24hrs = minDesksByTerminalAndQueue24Hrs(terminal)
      val maxDesksByQueue24Hrs = maxDesksByTerminalAndQueue24Hrs(terminal)
      (terminal, FlexedTerminalDeskLimits(terminalDesksByMinute, flexedQueuesPriority.toSet, minDesksByQueue24hrs, maxDesksByQueue24Hrs))
    }.toMap
}

object FlexedPortDeskRecsProvider {
  def apply(airportConfig: AirportConfig, tryCrunch: TryCrunch): FlexedPortDeskRecsProvider =
    FlexedPortDeskRecsProvider(airportConfig.queuesByTerminal,
                               airportConfig.divertedQueues,
                               airportConfig.minDesksByTerminalAndQueue24Hrs,
                               airportConfig.maxDesksByTerminalAndQueue24Hrs,
                               airportConfig.desksByTerminal,
                               airportConfig.flexedQueuesPriority,
                               airportConfig.slaByQueue,
                               airportConfig.terminalProcessingTimes,
                               airportConfig.minutesToCrunch,
                               airportConfig.crunchOffsetMinutes,
                               airportConfig.eGateBankSize,
                               tryCrunch)
}
