package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{SimulationMinute, SimulationMinutes, TQM}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.Crunch.LoadMinute

import scala.collection.immutable.{Map, NumericRange}

trait PortDesksAndWaitsProviderLike {
  val minutesToCrunch: Int
  val crunchOffsetMinutes: Int

  def flightsToLoads(flights: FlightsWithSplits,
                     crunchStartMillis: MillisSinceEpoch): Map[TQM, LoadMinute]

  def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                   loads: Map[TQM, LoadMinute],
                   deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike]): DeskRecMinutes

  def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                         loadsByQueue: Map[TQM, LoadMinute],
                         deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike]): SimulationMinutes
}
