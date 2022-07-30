package drt.shared.coachTime

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.redlist.{LhrRedListDates, LhrTerminalTypes}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals._

object LhrCoachWalkTime {
  val coachTransfers: List[CoachTransfer] = List(
    CoachTransfer(T2, 10 * 60000L, 11 * 60000L, 15 * 60000L),
    CoachTransfer(T3, 10 * 60000L, 11 * 60000L, 15 * 60000L),
    CoachTransfer(T5, 10 * 60000L, 17 * 60000L, 15 * 60000L)
  )
}

case class LhrCoachWalkTime(lhrRedListDates: LhrRedListDates, coachTransfers: List[CoachTransfer]) extends CoachWalkTime {
  private val lhrTerminalTypes: LhrTerminalTypes = LhrTerminalTypes(lhrRedListDates)

  def walkTime(flight: Arrival): Option[MillisSinceEpoch] = {
    if (lhrTerminalTypes.lhrNonRedListTerminalsForDate(flight.Scheduled).contains(flight.Terminal)) {
      coachTransfers.filter(_.fromTerminal == flight.Terminal)
        .map(ct => ct.passengerLoadingTime + ct.transferTime + ct.fromCoachGateWalkTime)
        .headOption
    } else None
  }
}
