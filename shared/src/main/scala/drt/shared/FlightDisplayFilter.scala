package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.redlist.LhrTerminalTypes

sealed trait FlightDisplayFilter {
  val forTerminalIncludingIncomingDiversions: (Iterable[ApiFlightWithSplits], Terminal) => Iterable[ApiFlightWithSplits] =
    (flights: Iterable[ApiFlightWithSplits], terminal: Terminal) => flights.filter { fws =>
      filterIncludingIncomingDivertedRedListFlights(fws, terminal)
    }

  val forTerminalReflectingDiversions: (Iterable[ApiFlightWithSplits], Terminal) => Iterable[ApiFlightWithSplits] =
    (flights: Iterable[ApiFlightWithSplits], terminal: Terminal) => flights.filter { fws =>
      filterReflectingDivertedRedListFlights(fws, terminal)
    }

  val filterIncludingIncomingDivertedRedListFlights: (ApiFlightWithSplits, Terminal) => Boolean

  val filterReflectingDivertedRedListFlights: (ApiFlightWithSplits, Terminal) => Boolean
}

case object DefaultFlightDisplayFilter extends FlightDisplayFilter {
  override val filterIncludingIncomingDivertedRedListFlights: (ApiFlightWithSplits, Terminal) => Boolean =
    (fws, terminal) => fws.apiFlight.Terminal == terminal

  override val filterReflectingDivertedRedListFlights: (ApiFlightWithSplits, Terminal) => Boolean =
    (fws, terminal) => fws.apiFlight.Terminal == terminal
}

case class LhrFlightDisplayFilter(isRedListOrigin: (PortCode, MillisSinceEpoch) => Boolean, terminalTypes: LhrTerminalTypes) extends FlightDisplayFilter {
  override val filterIncludingIncomingDivertedRedListFlights: (ApiFlightWithSplits, Terminal) => Boolean = (fws: ApiFlightWithSplits, terminal: Terminal) =>
    fws.apiFlight.Terminal == terminal || isIncomingDivertedRedListPaxFlight(fws, terminal)

  override val filterReflectingDivertedRedListFlights: (ApiFlightWithSplits, Terminal) => Boolean = (fws: ApiFlightWithSplits, terminal: Terminal) => {
    terminalTypes.lhrRedListTerminalForDate(fws.apiFlight.Scheduled) match {
      case Some(redListTerminal) =>
        if (terminal == redListTerminal)
          fws.apiFlight.Terminal == terminal || isIncomingDivertedRedListPaxFlight(fws, terminal)
        else
          fws.apiFlight.Terminal == terminal && !isOutgoingRedListPaxFlight(fws, terminal)
      case None =>
        fws.apiFlight.Terminal == terminal
    }
  }

  private def isIncomingDivertedRedListPaxFlight(fws: ApiFlightWithSplits, terminal: Terminal) = {
    def isRedListTerminal = terminalTypes.lhrRedListTerminalForDate(fws.apiFlight.Scheduled).contains(terminal)

    def flightIsNonRedListTerminal: Boolean = terminalTypes
      .lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled)
      .contains(fws.apiFlight.Terminal)

    def flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin, fws.apiFlight.Scheduled)

    isRedListTerminal && flightIsNonRedListTerminal && flightIsRedListOrigin
  }

  private def isOutgoingRedListPaxFlight(fws: ApiFlightWithSplits, terminal: Terminal) = {
    val redListDiversionsActive = terminalTypes.lhrRedListTerminalForDate(fws.apiFlight.Scheduled).isDefined
    val isNonRedListTerminal = terminalTypes.lhrNonRedListTerminalsForDate(fws.apiFlight.Scheduled).contains(terminal)
    val flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin, fws.apiFlight.Scheduled)

    redListDiversionsActive && isNonRedListTerminal && flightIsRedListOrigin
  }
}