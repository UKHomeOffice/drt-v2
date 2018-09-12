package controllers

import org.specs2.mutable.Specification
import services.graphstages.StaffAssignmentHelper


class StaffAssignmentHelperSpec extends Specification {
  "ShiftsActor" should {
    "convert date and time into a timestamp" in {
      Some(1484906400000L) === StaffAssignmentHelper.dateAndTimeToMillis("20/01/17", "10:00")
    }
    "return None if the date is incorrectly formatted" in {
      None === StaffAssignmentHelper.dateAndTimeToMillis("adfgdfgdfg7", "10:00")
    }
    "get start and end date millis from the startDate, endTime and startTime when endTime is later than startTime" in {
      val result = StaffAssignmentHelper.startAndEndTimestamps("20/01/17", "10:00", "11:00")
      result === Tuple2(Some(1484906400000L), Some(1484910000000L))
    }
    "get start and end date millis from the startDate, endTime and startTime when endTime is earlier than startTime" in {
      val result = StaffAssignmentHelper.startAndEndTimestamps("20/01/17", "10:00", "09:00")
      result === Tuple2(Some(1484906400000L), Some(1484989200000L))
    }
    "get start and end date millis from the startDate, endTime and startTime given invalid data" in {
      val result = StaffAssignmentHelper.startAndEndTimestamps("jkhsdfjhdsf", "10:00", "09:00")
      result === Tuple2(None, None)
    }
    "convert timestamp to dateString" in {
      val timestamp = 1484906400000L

      StaffAssignmentHelper.dateString(timestamp) === "20/01/17"
    }
    "convert timestamp to timeString" in {
      val timestamp = 1484906400000L

      StaffAssignmentHelper.timeString(timestamp) === "10:00"
    }
  }
}
