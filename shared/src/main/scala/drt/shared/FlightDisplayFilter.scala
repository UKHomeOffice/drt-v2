package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.{T2, T3, T4, T5, Terminal}

sealed trait FlightDisplayFilter {
  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits]
}

case object DefaultFlightDisplayFilter extends FlightDisplayFilter {
  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter(_.apiFlight.Terminal == terminal)
}

case class LhrFlightDisplayFilter(isRedListOrigin: PortCode => Boolean, t4OpeningDate: MillisSinceEpoch) extends FlightDisplayFilter {
  def lhrRedListTerminalForDate(scheduled: MillisSinceEpoch): Terminal =
    if (scheduled < t4OpeningDate) T3 else T4

  val lhrNonRedListTerminals = List(T2, T3 ,T5)

  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter { fws =>
      fws.apiFlight.Terminal == terminal || isDivertedRedListPaxFlight(terminal, fws)
    }

  private def isDivertedRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) = {
    def isRedListTerminal = terminal == lhrRedListTerminalForDate(fws.apiFlight.Scheduled)
    def flightIsNonRedListTerminal: Boolean = lhrNonRedListTerminals.contains(fws.apiFlight.Terminal)
    def flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)
    isRedListTerminal && flightIsNonRedListTerminal && flightIsRedListOrigin
  }
}
