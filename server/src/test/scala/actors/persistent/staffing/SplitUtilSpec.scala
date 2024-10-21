package actors.persistent.staffing;


import drt.shared._
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

class SplitUtilSpec extends Specification {

  "SplitUtil" >> {
    "split a shift into 14 minutes interval shifts" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T12:00").millisSinceEpoch
      SplitUtil.splitIntoIntervals(StaffAssignment("test", T1, startTime, endTime, 3, None)) mustEqual List(
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:44:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:59:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:14:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:29:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:44:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:59:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:14:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:29:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:44:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:59:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:14:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:29:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:44:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:59:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:14:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:29:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:44:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:59:00Z").millisSinceEpoch, 3, None),
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
      val result: Seq[StaffAssignmentLike] = SplitUtil.applyUpdatedShifts(
        Seq.empty,
        Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))
      result.toSet mustEqual expectedResult
    }

  "update shift if shift exists before" >> {

    val existingShiftStartTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
    val existingShiftEndTime = SDate(s"2017-01-01T8:00").millisSinceEpoch

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
    val result: Seq[StaffAssignmentLike] = SplitUtil.applyUpdatedShifts(
      Seq(StaffAssignment("Morning", T1, existingShiftStartTime, existingShiftEndTime, 5, None)),
      Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))
    result.toSet mustEqual expectedResult
  }


    "update shift if shift exists before" >> {

      val existingShiftStartTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val existingShiftEndTime = SDate(s"2017-01-01T9:00").millisSinceEpoch

      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T8:00").millisSinceEpoch
      val expectedResult = Set(
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 10, None))
      val result: Seq[StaffAssignmentLike] = SplitUtil.applyUpdatedShifts(
        Seq(StaffAssignment("Morning", T1, existingShiftStartTime, existingShiftEndTime, 10, None)),
        Seq(StaffAssignment("Morning", T1, startTime, endTime, 5, None)))
      result.toSet mustEqual expectedResult
    }

}

}