package drt.shared

import drt.shared.Terminals._
import drt.shared.redlist.LhrTerminalTypes

sealed trait FlightDisplayFilter {
  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits]
}

case object DefaultFlightDisplayFilter extends FlightDisplayFilter {
  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter(_.apiFlight.Terminal == terminal)
}

case class LhrFlightDisplayFilter(isRedListOrigin: PortCode => Boolean, terminalTypes: LhrTerminalTypes) extends FlightDisplayFilter {
  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter { fws =>
      fws.apiFlight.Terminal == terminal || isDivertedRedListPaxFlight(terminal, fws)
    }

  private def isDivertedRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) = {
    def isRedListTerminal = terminalTypes.lhrRedListTerminalForDate(fws.apiFlight.Scheduled).contains(terminal)

    def flightIsNonRedListTerminal: Boolean = terminalTypes
      .lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled)
      .contains(fws.apiFlight.Terminal)

    def flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)

    isRedListTerminal && flightIsNonRedListTerminal && flightIsRedListOrigin
  }
}
