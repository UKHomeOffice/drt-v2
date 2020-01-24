package services.crunch.deskrecs

import drt.shared.CrunchApi
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits

trait PortDeskRecsProviderLike {
  val minutesToCrunch: Int
  val crunchOffsetMinutes: Int

  def flightsToDeskRecs(flights: FlightsWithSplits, crunchStartMillis: MillisSinceEpoch): CrunchApi.DeskRecMinutes
}

