package drt.shared

import drt.shared.Terminals.Terminal
import drt.shared.redlist.LhrTerminalTypes

sealed trait FlightDisplayFilter {
  val forTerminalIncludingIncomingDiversions: (Iterable[ApiFlightWithSplits], Terminal) => Iterable[ApiFlightWithSplits] =
    (flights: Iterable[ApiFlightWithSplits], terminal: Terminal) => flights.filter { fws =>
      filterIncludingIncomingDivertedRedListPaxFlight(terminal, fws)
    }

  val forTerminalExcludingOutgoingDiversions: (Iterable[ApiFlightWithSplits], Terminal) => Iterable[ApiFlightWithSplits] =
    (flights: Iterable[ApiFlightWithSplits], terminal: Terminal) => flights.filter { fws =>
      filterExcludingOutgoingDivertedRedListPaxFlight(terminal, fws)
    }

  val filterIncludingIncomingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean

  val filterExcludingOutgoingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean
}

case object DefaultFlightDisplayFilter extends FlightDisplayFilter {
  override val filterIncludingIncomingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean =
    (terminal, fws) => fws.apiFlight.Terminal == terminal

  override val filterExcludingOutgoingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean =
    (terminal, fws) => fws.apiFlight.Terminal == terminal
}

case class LhrFlightDisplayFilter(isRedListOrigin: PortCode => Boolean, terminalTypes: LhrTerminalTypes) extends FlightDisplayFilter {
  val filterIncludingIncomingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean = (terminal: Terminal, fws: ApiFlightWithSplits) =>
    fws.apiFlight.Terminal == terminal || isIncomingDivertedRedListPaxFlight(terminal, fws)

  val filterExcludingOutgoingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean = (terminal: Terminal, fws: ApiFlightWithSplits) =>
    fws.apiFlight.Terminal == terminal && !isOutgoingRedListPaxFlight(terminal, fws)

  private def isIncomingDivertedRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) = {
    def isRedListTerminal = terminalTypes.lhrRedListTerminalForDate(fws.apiFlight.Scheduled).contains(terminal)

    def flightIsNonRedListTerminal: Boolean = terminalTypes
      .lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled)
      .contains(fws.apiFlight.Terminal)

    def flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)

    isRedListTerminal && flightIsNonRedListTerminal && flightIsRedListOrigin
  }

  private def isOutgoingRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) = {
    val redListDiversionsActive = terminalTypes.lhrRedListTerminalForDate(fws.apiFlight.Scheduled).isDefined
    val isNonRedListTerminal = terminalTypes.lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled).contains(terminal)
    val flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)

    redListDiversionsActive && isNonRedListTerminal && flightIsRedListOrigin
  }
}
