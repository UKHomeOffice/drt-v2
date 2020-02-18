package services.crunch.desklimits.flexed

import drt.shared.AirportConfig
import drt.shared.Queues.{EeaDesk, NonEeaDesk, Queue}
import drt.shared.Terminals.Terminal
import services.crunch.desklimits.TerminalDeskLimitsLike

import scala.collection.immutable.Map

case class FlexedPortDeskLimits(desksByTerminalByMinute: Map[Terminal, List[Int]],
                                minDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                maxDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                                flexedQueues: Set[Queue]) {
  def maxDesksByTerminal: Map[Terminal, TerminalDeskLimitsLike] = desksByTerminalByMinute
    .map { case (terminal, terminalDesksByMinute) =>
      for {
        minDesksByQueue24Hrs <- minDesksByTerminalAndQueue24Hrs.get(terminal)
        maxDesksByQueue24Hrs <- maxDesksByTerminalAndQueue24Hrs.get(terminal)
      } yield (terminal, FlexedTerminalDeskLimits(terminalDesksByMinute, flexedQueues, minDesksByQueue24Hrs, maxDesksByQueue24Hrs))
    }
    .collect { case Some(terminalDesks) => terminalDesks }
    .toMap
}

object FlexedPortDeskLimits {
  def apply(airportConfig: AirportConfig): FlexedPortDeskLimits = {
    val minDesks24Hrs = airportConfig.minDesksByTerminalAndQueue24Hrs
    val maxDesks24Hrs = airportConfig.maxDesksByTerminalAndQueue24Hrs
    val desksByTerminal24Hrs = airportConfig.desksByTerminal.mapValues(desks => List.fill(airportConfig.minutesToCrunch)(desks))
    FlexedPortDeskLimits(desksByTerminal24Hrs, minDesks24Hrs, maxDesks24Hrs, Set(EeaDesk, NonEeaDesk))
  }
}
