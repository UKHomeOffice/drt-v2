package actors.persistent.staffing

import akka.actor.Props
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.StaffAssignment
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

class FixedPointsActorSpec extends CrunchTestLike with ImplicitSender {
}
