package controllers

import org.joda.time.DateTime
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch

class ApplicationSpec extends CrunchTestLike {
  "Application" should {
    "isInRangeOnDay should be True of the last date-time in a given date range" in {
      val anHourAgo: DateTime = DateTime.now.minusHours(1)
      val now: DateTime = DateTime.now
      val startDateTime = SDate(anHourAgo)
      val endDateTime = SDate(now)
      Crunch.isInRangeOnDay(startDateTime, endDateTime)(endDateTime) must beTrue
    }
  }
}
