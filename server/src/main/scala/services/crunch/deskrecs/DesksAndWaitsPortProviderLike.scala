package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.TQM
import drt.shared.Terminals.Terminal
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.Crunch.LoadMinute

import scala.collection.immutable.{Map, NumericRange}

trait DesksAndWaitsPortProviderLike {
  val minutesToCrunch: Int
  val crunchOffsetMinutes: Int

  def flightsToLoads(flights: FlightsWithSplits,
                     crunchStartMillis: MillisSinceEpoch): Map[TQM, LoadMinute]

  def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                   loads: Map[TQM, LoadMinute],
                   maxDesksByTerminal: Map[Terminal, TerminalDeskLimitsLike]): DeskRecMinutes
}
