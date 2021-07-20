package drt.shared.coachTime

import drt.shared.{ApiFlightWithSplits, PortCode}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals._
import drt.shared.api.Arrival


trait CoachWalkTime {

  def coachTransferTerminalWaitTime: List[CoachTransfer]

  def walkTime(flight: Arrival): Option[MillisSinceEpoch]

  def displayWalkTime(flight: Arrival): Option[String]

  def flightFilterForTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal, isRedListOrigin: PortCode => Boolean): Iterable[ApiFlightWithSplits]

}

object CoachWalkTime {
  def apply(portCode: PortCode, t4OpeningDate: MillisSinceEpoch): CoachWalkTime = {
    portCode match {
      case PortCode("LHR") => new LhrCoachWalkTime(t4OpeningDate)
      case _ => new DefaultCoachWalkTime()
    }
  }
}





