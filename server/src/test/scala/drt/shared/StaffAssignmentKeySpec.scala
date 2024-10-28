package drt.shared;


import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

class StaffAssignmentKeySpec extends AnyFlatSpec with Matchers {

  "StaffAssignment key" should "generate correct key for 14-minute intervals" in {
        val assignment1 = StaffAssignment("test", T1, SDate("2023-10-01T07:00:00Z").millisSinceEpoch, SDate("2023-10-01T07:14:00Z").millisSinceEpoch, 3, None)
        val assignment2 = StaffAssignment("test", T1, SDate("2023-10-01T07:15:00Z").millisSinceEpoch, SDate("2023-10-01T07:29:00Z").millisSinceEpoch, 3, None)
        val assignment3 = StaffAssignment("test", T1, SDate("2023-10-01T07:30:00Z").millisSinceEpoch, SDate("2023-10-01T07:44:00Z").millisSinceEpoch, 3, None)
        val assignment4 = StaffAssignment("test", T1, SDate("2023-10-01T07:45:00Z").millisSinceEpoch, SDate("2023-10-01T07:59:00Z").millisSinceEpoch, 3, None)
        val assignment5 = StaffAssignment("test", T1, SDate("2023-10-01T07:46:00Z").millisSinceEpoch, SDate("2023-10-01T07:59:00Z").millisSinceEpoch, 3, None)
        val assignment6 = StaffAssignment("test", T1, SDate("2023-10-01T07:46:00Z").millisSinceEpoch, SDate("2023-10-01T07:48:00Z").millisSinceEpoch, 3, None)

        assignment1.key shouldEqual StaffAssignmentKey(T1, SDate("2023-10-01T07:00:00Z").millisSinceEpoch, SDate("2023-10-01T07:14:00Z").millisSinceEpoch)
        assignment2.key shouldEqual StaffAssignmentKey(T1, SDate("2023-10-01T07:15:00Z").millisSinceEpoch, SDate("2023-10-01T07:29:00Z").millisSinceEpoch)
        assignment3.key shouldEqual StaffAssignmentKey(T1, SDate("2023-10-01T07:30:00Z").millisSinceEpoch, SDate("2023-10-01T07:44:00Z").millisSinceEpoch)
        assignment4.key shouldEqual StaffAssignmentKey(T1, SDate("2023-10-01T07:45:00Z").millisSinceEpoch, SDate("2023-10-01T07:59:00Z").millisSinceEpoch)
        assignment5.key shouldEqual StaffAssignmentKey(T1, SDate("2023-10-01T07:45:00Z").millisSinceEpoch, SDate("2023-10-01T07:59:00Z").millisSinceEpoch)
        assignment6.key shouldEqual StaffAssignmentKey(T1, SDate("2023-10-01T07:45:00Z").millisSinceEpoch, SDate("2023-10-01T07:59:00Z").millisSinceEpoch)

  }
}