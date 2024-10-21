package actors.persistent.staffing;


import org.specs2.mutable.Specification
import drt.shared._
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, TimeZoneHelper}

class SplitUtilSpec extends Specification {


//  val assignments = Seq(
//    StaffAssignment("test", T1, 1483267500000L, 1483268400000L, 3, None),
//    StaffAssignment("test", T1, 1483257600000L, 1483258500000L, 3, None),
//    StaffAssignment("test", T1, 1483271100000L, 1483272000000L, 3, None),
//    StaffAssignment("test", T1, 1483261200000L, 1483262100000L, 3, None),
//    StaffAssignment("test", T1, 1483263900000L, 1483264800000L, 3, None),
//    StaffAssignment("test", T1, 1483268400000L, 1483269300000L, 3, None),
//    StaffAssignment("test", T1, 1483270200000L, 1483271100000L, 3, None),
//    StaffAssignment("test", T1, 1483256700000L, 1483257600000L, 3, None),
//    StaffAssignment("test", T1, 1483260300000L, 1483261200000L, 3, None),
//    StaffAssignment("test", T1, 1483264800000L, 1483265700000L, 3, None),
//    StaffAssignment("test", T1, 1483269300000L, 1483270200000L, 3, None),
//    StaffAssignment("test", T1, 1483255800000L, 1483256700000L, 3, None),
//    StaffAssignment("test", T1, 1483265700000L, 1483266600000L, 3, None),
//    StaffAssignment("test", T1, 1483262100000L, 1483263000000L, 3, None),
//    StaffAssignment("test", T1, 1483259400000L, 1483260300000L, 3, None),
//    StaffAssignment("test", T1, 1483254900000L, 1483255800000L, 3, None),
//    StaffAssignment("test", T1, 1483266600000L, 1483267500000L, 3, None),
//    StaffAssignment("test", T1, 1483263000000L, 1483263900000L, 3, None),
//    StaffAssignment("test", T1, 1483258500000L, 1483259400000L, 3, None),
//    StaffAssignment("test", T1, 1483254000000L, 1483254900000L, 3, None)
//    ,
//  )
//  assignments.foreach { case assignment =>
//    println(s"StaffAssignment......(${assignment.name}, ${assignment.terminal}, ${SDate(assignment.start).toISOString}, ${SDate(assignment.end).toISOString}, ${assignment.numberOfStaff}, ${assignment.createdBy})")
//  }

  def printlnResult(assignments: Seq[StaffAssignmentLike]): Unit = {
    assignments.sortBy(_.start).foreach { case assignment =>
      println(s"StaffAssignment......(${assignment.name}, ${assignment.terminal}, ${SDate(assignment.start).toISOString}, ${SDate(assignment.end).toISOString}, ${assignment.numberOfStaff}, ${assignment.createdBy})")
    }
  }

  "SplitUtil" >> {
    "split a shift into 15 minutes interval shifts" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T12:00").millisSinceEpoch
      SplitUtil.splitIntoIntervals(StaffAssignment("test", T1, startTime, endTime, 3, None)) mustEqual List(
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, 3, None),
        StaffAssignment("test", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T12:00:00Z").millisSinceEpoch, 3, None),
      )
    }

    "update shift if nothing exists before" >> {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T9:00").millisSinceEpoch
      val expectedResult = Set(
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T09:00:00Z").millisSinceEpoch, 10, None))
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
      StaffAssignment("Morning", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, 10, None),
      StaffAssignment("Morning", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, 10, None),
      StaffAssignment("Morning", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, 10, None),
      StaffAssignment("Morning", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, 10, None),
      StaffAssignment("Morning", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, 10, None),
      StaffAssignment("Morning", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, 10, None),
      StaffAssignment("Morning", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, 10, None),
      StaffAssignment("Morning", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T09:00:00Z").millisSinceEpoch, 10, None))
    val result: Seq[StaffAssignmentLike] = SplitUtil.applyUpdatedShifts(
      Seq(StaffAssignment("Morning", T1, existingShiftStartTime, existingShiftEndTime, 5, None)),
      Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))
    printlnResult(result)
    result.toSet mustEqual expectedResult
  }
}

}