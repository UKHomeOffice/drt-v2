package drt.shared.coachTime

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default.{macroRW, _}

object CoachTransfer {
  implicit val rw: ReadWriter[CoachTransfer] = macroRW
}

case class CoachTransfer(fromTerminal: Terminal, passengerLoadingTime: Long, transferTime: Long, fromCoachGateWalkTime: Long)

