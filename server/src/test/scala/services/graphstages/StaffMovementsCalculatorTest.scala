package services.graphstages

import drt.shared.StaffMovement
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.ports.Terminals.T1

import java.util.UUID

object StaffMovementsCalculatorTest extends Specification {
  "Given some movements" >> {
    "When those movements don't fall on minute boundaries" >> {
      val startDate1 = SDate("2017-01-01T00:00:15").millisSinceEpoch
      val endDate1 = SDate("2017-01-01T00:02:15").millisSinceEpoch
      val uuid = UUID.randomUUID().toString
      val staffMovement1 = StaffMovement(T1, "some reason", startDate1, 1, uuid, None, None)
      val staffMovement2 = StaffMovement(T1, "some reason", endDate1, -1, uuid, None, None)
      val service = StaffMovementsCalculator(Seq(staffMovement1, staffMovement2))

      "I should not see them apply to the rounded minute before the start" >> {
        service.terminalStaffAt(T1, SDate("2016-12-31T23:59")) === 0
      }
      "I should see them apply to the rounded start minute" >> {
        service.terminalStaffAt(T1, SDate("2017-01-01T00:00")) === 1
      }
      "I should see them apply to the rounded minute before the end" >> {
        service.terminalStaffAt(T1, SDate("2017-01-01T00:01")) === 1
      }
      "I should not see them apply to the rounded end minute" >> {
        service.terminalStaffAt(T1, SDate("2017-01-01T00:02")) === 0
      }
    }
  }
}
