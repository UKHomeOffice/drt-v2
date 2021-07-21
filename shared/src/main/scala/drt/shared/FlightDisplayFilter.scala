package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals._

sealed trait FlightDisplayFilter {
  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits]
}

case object DefaultFlightDisplayFilter extends FlightDisplayFilter {
  def forTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal): Iterable[ApiFlightWithSplits] =
    flights.filter(_.apiFlight.Terminal == terminal)
}

trait LhrRedListDates {
  val t3RedListOpeningDate: MillisSinceEpoch
  val t4RedListOpeningDate: MillisSinceEpoch
}

case object LhrRedListDatesImpl extends LhrRedListDates {
  override val t3RedListOpeningDate = 1622502000000L // 2021-06-01 BST
  override val t4RedListOpeningDate = 1624921200000L // 2021-06-29 BST
}

case class LhrTerminalTypes(lhrRedListDates: LhrRedListDates) {

  def lhrRedListTerminalForDate(scheduled: MillisSinceEpoch): Option[Terminal] =
    if (scheduled < lhrRedListDates.t3RedListOpeningDate) None
    else if (scheduled < lhrRedListDates.t4RedListOpeningDate) Option(T3)
    else Option(T4)

  def lhrNonRedListTerminalsForDate(scheduled: MillisSinceEpoch): List[Terminal] =
    if (scheduled < lhrRedListDates.t3RedListOpeningDate) List()
    else if (scheduled < lhrRedListDates.t4RedListOpeningDate) List(T2, T5)
    else List(T2, T3, T5)
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
