package drt.shared.coachTime

import drt.shared.{ApiFlightWithSplits, PortCode}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival

class DefaultCoachWalkTime extends CoachWalkTime {

  override def coachTransferTerminalWaitTime: List[CoachTransfer] = List.empty[CoachTransfer]

  override def walkTime(flight: Arrival): Option[MillisSinceEpoch] = None

  override def displayWalkTime(flight: Arrival): Option[String] = None

  override def flightFilterForTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal, isRedListOrigin: PortCode => Boolean): Iterable[ApiFlightWithSplits] =
    flights.filter(_.apiFlight.Terminal == terminal)

}
