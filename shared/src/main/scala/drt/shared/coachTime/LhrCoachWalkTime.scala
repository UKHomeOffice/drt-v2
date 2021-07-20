package drt.shared.coachTime

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals._
import drt.shared.TimeUtil.millisToMinutes
import drt.shared.api.Arrival
import drt.shared.{ApiFlightWithSplits, MinuteAsAdjective, PortCode}

class LhrCoachWalkTime(t4OpeningDate: MillisSinceEpoch) extends CoachWalkTime {

  val greenTerminal = List(T1, T2, T3)

  val redTerminal = T4

  def redListTerminalForDate(scheduled: MillisSinceEpoch): Terminal =
    if (scheduled < t4OpeningDate) T3 else T4

  def greenListTerminals(scheduled: MillisSinceEpoch, t4OpeningDate: MillisSinceEpoch): List[Terminal] =
    if (scheduled > t4OpeningDate) List(T2, T3, T5) else List(T2, T5)

  val coachTransferTerminalWaitTime = List(
    CoachTransfer(T2, 10 * 60000L, 21 * 60000L, 900000L),
    CoachTransfer(T3, 10 * 60000L, 21 * 60000L, 900000L),
    CoachTransfer(T5, 10 * 60000L, 27 * 60000L, 900000L)
  )

  def walkTime(flight: Arrival): Option[MillisSinceEpoch] = {
    if (greenTerminal.contains(flight.Terminal) && coachTransferTerminalWaitTime.nonEmpty) {
      coachTransferTerminalWaitTime.filter(_.fromTerminal == flight.Terminal)
        .map(ct => ct.passengerLoadingTime + ct.transferTime + ct.fromCoachGateWalkTime)
        .headOption
    } else None
  }

  def displayWalkTime(flight: Arrival): Option[String] = {
    if (greenListTerminals(flight.Scheduled, t4OpeningDate).contains(flight.Terminal) && coachTransferTerminalWaitTime.nonEmpty) {
      coachTransferTerminalWaitTime.filter(_.fromTerminal == flight.Terminal)
        .map(ct => ct.passengerLoadingTime + ct.transferTime + ct.fromCoachGateWalkTime)
        .map(ct => MinuteAsAdjective(millisToMinutes(ct)).display + " walk time")
        .headOption
    } else None
  }

  def flightFilterForTerminal(flights: Iterable[ApiFlightWithSplits], terminal: Terminal, isRedListOrigin: PortCode => Boolean): Iterable[ApiFlightWithSplits] =
    flights.filter { fws =>
      fws.apiFlight.Terminal == terminal || isDivertedRedListPaxFlight(isRedListOrigin ,terminal, fws)
    }

  private def isDivertedRedListPaxFlight(isRedListOrigin: PortCode => Boolean,terminal: Terminal, fws: ApiFlightWithSplits) = {
    def isRedListTerminal = terminal == redListTerminalForDate(fws.apiFlight.Scheduled)

    def flightIsNonRedListTerminal: Boolean = greenListTerminals(fws.apiFlight.Scheduled, t4OpeningDate).contains(fws.apiFlight.Terminal)

    def flightIsRedListOrigin = isRedListOrigin(fws.apiFlight.Origin)

    isRedListTerminal && flightIsNonRedListTerminal && flightIsRedListOrigin
  }

}