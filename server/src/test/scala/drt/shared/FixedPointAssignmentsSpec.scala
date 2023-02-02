package drt.shared

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.T1

class FixedPointAssignmentsSpec extends Specification {
  "Given some assignments" >> {
    "When diff with assignments containing one fewer" >> {
      "Then the result should be the removed assignment" >> {
        val a1 = StaffAssignment("a1", T1, 0, 1, 1, None)
        val a2 = StaffAssignment("a2", T1, 0, 1, 1, None)
        val a3 = StaffAssignment("a3", T1, 0, 1, 1, None)

        val assignments = Seq(a1, a2, a3)
        val removed = Seq(a1, a2)

        val result = FixedPointAssignments(assignments).diff(FixedPointAssignments(removed))

        result mustEqual FixedPointAssignments(Seq(a3))
      }
    }
    "When diff with assignments containing one more" >> {
      "Then the result should be the additional assignment" >> {
        val a1 = StaffAssignment("a1", T1, 0, 1, 1, None)
        val a2 = StaffAssignment("a2", T1, 0, 1, 1, None)
        val a3 = StaffAssignment("a3", T1, 0, 1, 1, None)

        val assignments = Seq(a1, a2)
        val removed = Seq(a1, a2, a3)

        val result = FixedPointAssignments(assignments).diff(FixedPointAssignments(removed))

        result mustEqual FixedPointAssignments(Seq(a3))
      }
    }
    "When diff with empty assignments" >> {
      "Then the result should be the original assignments" >> {
        val a1 = StaffAssignment("a1", T1, 0, 1, 1, None)
        val a2 = StaffAssignment("a2", T1, 0, 1, 1, None)
        val a3 = StaffAssignment("a3", T1, 0, 1, 1, None)

        val assignments = Seq(a1, a2, a3)
        val removed = Seq()

        val result = FixedPointAssignments(assignments).diff(FixedPointAssignments(removed))

        result mustEqual FixedPointAssignments(Seq(a1, a2, a3))
      }
    }
  }
}
