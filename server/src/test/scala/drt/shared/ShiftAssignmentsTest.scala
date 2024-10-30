package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDateLike

object ShiftAssignmentsTest extends Specification {
  "Given some shift assignments" >> {
    "When those shift assignments don't fall on minute boundaries" >> {
      val startDate1 = SDate("2017-01-01T00:00:15").millisSinceEpoch
      val endDate1 = SDate("2017-01-01T00:02:15").millisSinceEpoch
      val shift = StaffAssignment("shift1", T1, startDate1, endDate1, 1, None)
      val service = ShiftAssignments(Seq(shift))
      val msToSdate: MillisSinceEpoch => SDateLike = millis => SDate(millis)

      "I should not see them apply to the rounded minute before the start" >> {
        service.terminalStaffAt(T1, SDate("2016-12-31T23:59"), msToSdate) === 0
      }
      "I should see them apply to the rounded start minute" >> {
        service.terminalStaffAt(T1, SDate("2017-01-01T00:00"), msToSdate) === 1
      }
      "I should see them apply to the rounded minute before the end" >> {
        service.terminalStaffAt(T1, SDate("2017-01-01T00:01"), msToSdate) === 1
      }
      "I should see them apply to the rounded end minute" >> {
        service.terminalStaffAt(T1, SDate("2017-01-01T00:02"), msToSdate) === 1
      }
      "I should not see them apply to the rounded minute after the end" >> {
        service.terminalStaffAt(T1, SDate("2017-01-01T00:03"), msToSdate) === 0
      }
    }
  }


  "Give shift with start and end date" >> {
    "Then split a shift into 14 minutes interval shifts" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T7:30").millisSinceEpoch
      StaffAssignment("test", T1, startTime, endTime, 3, None).splitIntoSlots(15) mustEqual List(
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 3, None)
      )
    }
  }

  "Given no shifts" >> {
    "Then update shift if nothing exists before" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T9:00").millisSinceEpoch
      val existingShifts = ShiftAssignments(Seq())
      val expectedResult = Set(
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 10, None))
      val result: ShiftAssignments = existingShifts.applyUpdates(
        Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))
      result.assignments.toSet mustEqual expectedResult
    }
  }

  "Given existing shifts" >> {
    "update shift if shift exists before" >> {

      val existingShiftStartTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val existingShiftEndTime = SDate(s"2017-01-01T9:00").millisSinceEpoch

      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T8:00").millisSinceEpoch
      val existingShifts = ShiftAssignments(StaffAssignment("Morning", T1, existingShiftStartTime, existingShiftEndTime, 10, None).splitIntoSlots(15))
      val expectedResult = Set(
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 10, None))
      val result: ShiftAssignments = existingShifts.applyUpdates(
        Seq(StaffAssignment("Morning", T1, startTime, endTime, 5, None)))
      result.assignments.toSet mustEqual expectedResult
    }
  }

  "Performance test" >> {
    "should not take too long to apply update to 6 months of 15 minute existing shifts" >> {
      val existingShifts = ShiftAssignments(
        (0 until 96 * 180).map { slot =>
          StaffAssignment(
            "Morning",
            T1,
            SDate(s"2017-01-01T07:00").addMinutes(15 * slot).millisSinceEpoch,
            SDate(s"2017-01-01T07:00").addMinutes((15 * slot) + 14).millisSinceEpoch,
            5,
            None)
        }
      )

      val updateShifts = Seq(
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T12:00").millisSinceEpoch, SDate(s"2017-01-01T18:59").millisSinceEpoch, 10, None)
      )

      val start = System.currentTimeMillis()
      existingShifts.applyUpdates(updateShifts)
      val end = System.currentTimeMillis()
      println(s"Time taken: ${end - start}")
      (end - start) must be_<(100L)
    }

  }

}
