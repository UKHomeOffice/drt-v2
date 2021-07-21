package drt.shared.coachTime

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.Arrival
import drt.shared.{LhrRedListDatesImpl, PortCode}


trait CoachWalkTime {

  def walkTime(flight: Arrival): Option[MillisSinceEpoch]

  def displayWalkTime(flight: Arrival): Option[String]

}

object CoachWalkTime {
  def apply(portCode: PortCode): CoachWalkTime = {
    portCode match {
      case PortCode("LHR") => LhrCoachWalkTime(LhrRedListDatesImpl, LhrCoachWalkTime.coachTransfers)
      case _ => DefaultCoachWalkTime
    }
  }
}





