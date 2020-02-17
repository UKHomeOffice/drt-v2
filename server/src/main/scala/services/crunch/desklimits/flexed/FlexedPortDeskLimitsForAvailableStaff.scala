package services.crunch.desklimits.flexed

import drt.shared.AirportConfig
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import services.crunch.desklimits.TerminalDeskLimitsLike

import scala.collection.immutable.Map

case class FlexedPortDeskLimitsForAvailableStaff(desksByTerminalByMinute: Map[Terminal, List[Int]],
                                                 minDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                                 maxDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                                 flexedQueues: Set[Queue]) {
  def maxDesksByTerminal(availableStaffByMinute: Map[Terminal, List[Int]]): Map[Terminal, TerminalDeskLimitsLike] = availableStaffByMinute
    .map { case (terminal, terminalStaffByMinute) =>
      for {
        minDesksByQueue24Hrs <- minDesksByTerminalAndQueue24Hrs.get(terminal)
        maxDesksByQueue24Hrs <- maxDesksByTerminalAndQueue24Hrs.get(terminal)
        terminalDesksByMinute <- desksByTerminalByMinute.get(terminal)
      } yield (terminal, FlexedTerminalDeskLimitsFromAvailableStaff(terminalStaffByMinute, terminalDesksByMinute, flexedQueues, minDesksByQueue24Hrs, maxDesksByQueue24Hrs))
    }
    .collect { case Some(terminalDesks) => terminalDesks }
    .toMap
}

object FlexedPortDeskLimitsForAvailableStaff {
  def apply(airportConfig: AirportConfig): FlexedPortDeskLimitsForAvailableStaff = {
    val minDesks24Hrs = airportConfig.minDesksByTerminalAndQueue24Hrs
    val maxDesks24Hrs = airportConfig.maxDesksByTerminalAndQueue24Hrs
    val desksByTerminalByMinute = airportConfig.desksByTerminal.mapValues(d => List.fill(airportConfig.minutesToCrunch)(d))
    FlexedPortDeskLimitsForAvailableStaff(desksByTerminalByMinute, minDesks24Hrs, maxDesks24Hrs, airportConfig.flexedQueuesPriority.toSet)
  }
}
