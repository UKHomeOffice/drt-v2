package services.crunch.desklimits.fixed

import drt.shared.AirportConfig
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import services.crunch.desklimits.TerminalDeskLimitsLike

import scala.collection.immutable.Map

case class FixedPortDeskLimits(terminals: Iterable[Terminal],
                               minDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                               maxDesksByTerminalAndQueue24Hrs: Map[Terminal, Map[Queue, IndexedSeq[Int]]]) {
  def maxDesksByTerminal: Map[Terminal, TerminalDeskLimitsLike] = {
    val maybeDesks = for {
      terminal <- terminals
      minDesks <- minDesksByTerminalAndQueue24Hrs.get(terminal)
      maxDesks <- maxDesksByTerminalAndQueue24Hrs.get(terminal)
    } yield (terminal, FixedTerminalDeskLimits(minDesks, maxDesks))
    maybeDesks.toMap
  }
}

object FixedPortDeskLimits {
  def apply(airportConfig: AirportConfig): FixedPortDeskLimits =
    FixedPortDeskLimits(airportConfig.terminals, airportConfig.minDesksByTerminalAndQueue24Hrs, airportConfig.maxDesksByTerminalAndQueue24Hrs)
}
