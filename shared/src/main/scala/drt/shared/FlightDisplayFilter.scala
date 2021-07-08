package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.{T2, T3, T4, T5, Terminal}

sealed trait FlightDisplayFilter {
  def forTerminalIncludingIncomingDiversions(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits]
  def forTerminalExcludingOutgoingDiversions(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits]
}

case object DefaultFlightDisplayFilter extends FlightDisplayFilter {
  def forTerminalIncludingIncomingDiversions(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter(_.apiFlight.Terminal == terminal)
  def forTerminalExcludingOutgoingDiversions(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter(_.apiFlight.Terminal == terminal)
}

case class LhrFlightDisplayFilter(isRedListOrigin: PortCode => Boolean, t4OpeningDate: MillisSinceEpoch) extends FlightDisplayFilter {
  def lhrRedListTerminalForDate(scheduled: MillisSinceEpoch): Terminal =
    if (scheduled < t4OpeningDate) T3 else T4

  val lhrNonRedListTerminals = List(T2, T5)

  def forTerminalIncludingIncomingDiversions(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter { fws =>
      fws.apiFlight.Terminal == terminal || isIncomingDivertedRedListPaxFlight(terminal, fws)
    }

  def forTerminalExcludingOutgoingDiversions(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter { fws =>
      fws.apiFlight.Terminal == terminal && !isOutgoingRedListPaxFlight(terminal, fws)
    }

  private def isIncomingDivertedRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) = {
    def isRedListTerminal = terminal == lhrRedListTerminalForDate(fws.apiFlight.Scheduled)
    def flightIsNonRedListTerminal: Boolean = lhrNonRedListTerminals.contains(fws.apiFlight.Terminal)
    def flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)
    isRedListTerminal && flightIsNonRedListTerminal && flightIsRedListOrigin
  }

  private def isOutgoingRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) = {
    def isNonRedListTerminal = lhrNonRedListTerminals.contains(terminal)
    def flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)
    isNonRedListTerminal && flightIsRedListOrigin
  }
}
