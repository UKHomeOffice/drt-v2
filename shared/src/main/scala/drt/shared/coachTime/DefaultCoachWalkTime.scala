package drt.shared.coachTime

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals.Arrival

object DefaultCoachWalkTime extends CoachWalkTime {
  override def walkTime(flight: Arrival): Option[MillisSinceEpoch] = None
}
