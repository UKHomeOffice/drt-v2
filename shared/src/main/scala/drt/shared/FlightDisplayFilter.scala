package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.{T2, T3, T4, T5, Terminal}

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

case class LhrFlightDisplayFilter(isRedListOrigin: PortCode => Boolean,
                                  t3OpeningDate: MillisSinceEpoch,
                                  t4OpeningDate: MillisSinceEpoch) extends FlightDisplayFilter {
  def lhrRedListTerminalForDate(scheduled: MillisSinceEpoch): Option[Terminal] = {
    if (t3OpeningDate <= scheduled && scheduled < t4OpeningDate) Option(T3)
    else if (t4OpeningDate <= scheduled) Option(T4)
    else None
  }

  val lhrNonRedListTerminals = List(T2, T5)

  val filterIncludingIncomingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean = (terminal: Terminal, fws: ApiFlightWithSplits) =>
    fws.apiFlight.Terminal == terminal || isIncomingDivertedRedListPaxFlight(terminal, fws)

  val filterExcludingOutgoingDivertedRedListPaxFlight: (Terminal, ApiFlightWithSplits) => Boolean = (terminal: Terminal, fws: ApiFlightWithSplits) =>
    fws.apiFlight.Terminal == terminal && !isOutgoingRedListPaxFlight(terminal, fws)

  private def isIncomingDivertedRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) =
    lhrRedListTerminalForDate(fws.apiFlight.Scheduled).exists { redListTerminal =>
      val isRedListTerminal = terminal == redListTerminal
      val flightIsNonRedListTerminal: Boolean = lhrNonRedListTerminals.contains(fws.apiFlight.Terminal)
      val flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)

      isRedListTerminal && flightIsNonRedListTerminal && flightIsRedListOrigin
    }

  private def isOutgoingRedListPaxFlight(terminal: Terminal, fws: ApiFlightWithSplits) = {
    val redListDiversionsActive = lhrRedListTerminalForDate(fws.apiFlight.Scheduled).isDefined
    val isNonRedListTerminal = lhrNonRedListTerminals.contains(terminal)
    val flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)

    redListDiversionsActive && isNonRedListTerminal && flightIsRedListOrigin
  }
}
