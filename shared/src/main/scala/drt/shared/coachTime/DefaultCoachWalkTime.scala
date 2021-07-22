package drt.shared.coachTime

import drt.shared.{ApiFlightWithSplits, PortCode}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival

object DefaultCoachWalkTime extends CoachWalkTime {
  override def walkTime(flight: Arrival): Option[MillisSinceEpoch] = None
}
