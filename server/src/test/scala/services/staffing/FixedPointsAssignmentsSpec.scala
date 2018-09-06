package services.staffing

import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.{Crunch, StaffAssignmentParser, StaffAssignmentServiceWithoutDates}


class FixedPointsAssignmentsSpec extends Specification {

  "When checking fixed points at a point in time" >> {
    "Given 2 fixed points starting at 01:00 and ending at 12:00 then I should expect 2018-05-22T03:00:00Z to return 2" >> {
      val fixedPointsString = "Roving Officer, T1, 22/05/18, 01:00, 12:00, 2"
      val parsedAssignments = StaffAssignmentParser(fixedPointsString).parsedAssignments
      val fixedPointsAssignmentService = StaffAssignmentServiceWithoutDates(parsedAssignments).get

      val result = fixedPointsAssignmentService.terminalStaffAt("T1", SDate("2018-05-22T03:00:00Z").millisSinceEpoch)
      val expected = 2

      result === expected
    }

  "Given 2 fixed points starting at 00:00 and ending at 02:00 then I should expect 2018-01-22T01:00:00Z to return 2 during UTC" >> {
      val fixedPointsString = "Roving Officer, T1, 22/01/18, 00:00, 02:00, 2"
      val parsedAssignments = StaffAssignmentParser(fixedPointsString).parsedAssignments
      val fixedPointsAssignmentService = StaffAssignmentServiceWithoutDates(parsedAssignments).get

      val result = fixedPointsAssignmentService.terminalStaffAt("T1", SDate("2018-01-22T01:00:00Z").millisSinceEpoch)
      val expected = 2

      result === expected
    }

  "Given 2 fixed points starting at 00:00 and ending at 02:00 then I should expect 2018-05-22T01:00:00Z to return 2" >> {
      val fixedPointsString = "Roving Officer, T1, 22/05/18, 00:00, 02:00, 2"
      val parsedAssignments = StaffAssignmentParser(fixedPointsString).parsedAssignments
      val fixedPointsAssignmentService = StaffAssignmentServiceWithoutDates(parsedAssignments).get

      val result = fixedPointsAssignmentService.terminalStaffAt("T1", SDate("2018-05-22T01:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch)
      val expected = 2

      result === expected
    }
  }
}
