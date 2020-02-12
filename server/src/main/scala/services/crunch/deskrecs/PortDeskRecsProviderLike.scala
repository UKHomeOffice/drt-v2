package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{CrunchApi, TQM}
import services.crunch.deskrecs.StaffProviders.MaxDesksProvider
import services.graphstages.Crunch.LoadMinute

import scala.collection.immutable.{Map, NumericRange}

trait PortDeskRecsProviderLike {
  val minutesToCrunch: Int
  val crunchOffsetMinutes: Int

  def flightsToDeskRecs(flights: FlightsWithSplits,
                        crunchStartMillis: MillisSinceEpoch,
                        maxDesksProvider: Terminal => MaxDesksProvider): CrunchApi.DeskRecMinutes

  def loadsToDesks(maxDesksProvider: Terminal => MaxDesksProvider,
                   minuteMillis: NumericRange[MillisSinceEpoch],
                   terminalsToCrunch: Set[Terminal],
                   loadsWithDiverts: Map[TQM, LoadMinute]): DeskRecMinutes
}

