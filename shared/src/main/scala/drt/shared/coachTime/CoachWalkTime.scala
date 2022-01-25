package drt.shared.coachTime

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.redlist.LhrRedListDatesImpl
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.PortCode


trait CoachWalkTime {
  def walkTime(flight: Arrival): Option[MillisSinceEpoch]
}

object CoachWalkTime {
  def apply(portCode: PortCode): CoachWalkTime = {
    portCode match {
      case PortCode("LHR") => LhrCoachWalkTime(LhrRedListDatesImpl, LhrCoachWalkTime.coachTransfers)
      case _ => DefaultCoachWalkTime
    }
  }
}





