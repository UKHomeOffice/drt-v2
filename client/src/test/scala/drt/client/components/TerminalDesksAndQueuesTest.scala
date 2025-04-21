package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.client.services.MovementMinute
import drt.shared.CrunchApi.StaffMinute
import drt.shared.TM
import uk.gov.homeoffice.drt.ports.Terminals.T1
import utest.{TestSuite, Tests, test}

object TerminalDesksAndQueuesTest extends TestSuite {
  def tests: Tests = Tests {
    test("staffMinutesWithLocalUpdates") {
      val minute = SDate("2025-04-21T12:00Z").millisSinceEpoch
      val createdAt = SDate("2025-04-21T12:00Z").millisSinceEpoch
      val staffMinute = StaffMinute(T1, minute, 5, 1, 0, Option(createdAt))
      val movementMinute = MovementMinute(T1, minute, 1, createdAt + 1000)
      val updatedSm = TerminalDesksAndQueues.staffMinutesWithLocalUpdates(
        Map(TM(movementMinute.terminal, movementMinute.minute) -> Seq(movementMinute)),
        staffMinute
      )
      assert(updatedSm.movements == staffMinute.movements + movementMinute.staff)
      assert(updatedSm.availableAtPcp == staffMinute.availableAtPcp + movementMinute.staff)
    }
  }
}
