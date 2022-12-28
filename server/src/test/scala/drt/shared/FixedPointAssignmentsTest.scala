package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDateLike

object FixedPointAssignmentsTest extends Specification {
  "Given some fixed points assignments" >> {
    "When those fixed points assignments don't fall on minute boundaries" >> {
      val startDate1 = SDate("2017-01-01T00:00:15").millisSinceEpoch
      val endDate1 = SDate("2017-01-01T00:02:15").millisSinceEpoch
      val fixedPoints = StaffAssignment("fixed points 1", T1, startDate1, endDate1, 1, None)
      val service = FixedPointAssignments(Seq(fixedPoints))
      val msToSdate: MillisSinceEpoch => SDateLike = millis => SDate(millis)

      "I should not see them apply to the rounded minute before the start" >> {
        service.terminalStaffAt(T1, SDate("2017-02-02T23:59"), msToSdate) === 0
      }
      "I should see them apply to the rounded start minute, even on a different day" >> {
        service.terminalStaffAt(T1, SDate("2017-02-03T00:00"), msToSdate) === 1
      }
      "I should see them apply to the rounded minute before the end, even on a different day" >> {
        service.terminalStaffAt(T1, SDate("2017-02-03T00:01"), msToSdate) === 1
      }
      "I should see them apply to the rounded end minute, even on a different day" >> {
        service.terminalStaffAt(T1, SDate("2017-02-03T00:02"), msToSdate) === 1
      }
      "I should not see them apply to the rounded minute after the end" >> {
        service.terminalStaffAt(T1, SDate("2017-02-03T00:03"), msToSdate) === 0
      }
    }
  }

}
