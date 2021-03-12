package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{EGate, Queue, QueueStatus}
import drt.shared.Terminals.Terminal
import drt.shared.{SimulationMinute, SimulationMinutes, TQM}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.graphstages.Crunch.LoadMinute

import scala.collection.immutable.{Map, NumericRange}

trait PortDesksAndWaitsProviderLike {
  val minutesToCrunch: Int
  val crunchOffsetMinutes: Int
  val queueStatusAt: (Terminal, Queue, MillisSinceEpoch) => QueueStatus

  def flightsToLoads(flights: FlightsWithSplits): Map[TQM, LoadMinute]

//  def flightsToDynamicLoads(flights: FlightsWithSplits): Map[TQM, LoadMinute]

  def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                   loads: Map[TQM, LoadMinute],
                   deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike]): DeskRecMinutes

  def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                         loadsByQueue: Map[TQM, LoadMinute],
                         deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike]): SimulationMinutes
}
