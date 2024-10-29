package actors.persistent.staffing;


import drt.shared._
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

class SplitUtilSpec extends Specification {

  "SplitUtil" >> {
    "split a shift into 14 minutes interval shifts" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T7:30").millisSinceEpoch
      SplitUtil.splitIntoIntervals(StaffAssignment("test", T1, startTime, endTime, 3, None)) mustEqual List(
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 3, None)
      )
    }

    "update shift if nothing exists before" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T9:00").millisSinceEpoch
      val expectedResult = Set(
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 10, None))

      val result: Seq[StaffAssignmentLike] = ShiftAssignments(Seq.empty)
        .applyUpdates(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None))).assignments

      result.toSet mustEqual expectedResult
    }

    "update shift if shift exists before" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T9:00").millisSinceEpoch
      val expectedResult = Set(
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 10, None))

      val result: Seq[StaffAssignmentLike] = ShiftAssignments(Seq(
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
      ))
        .applyUpdates(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None))).assignments

      result.toSet mustEqual expectedResult
    }

    "should not take too long to apply update to 6 months of 15 minute existing shifts" in {
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
