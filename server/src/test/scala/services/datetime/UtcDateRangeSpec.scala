package services.datetime

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import services.graphstages.Crunch

class UtcDateRangeSpec extends Specification {
  val startDateUtc = "2020-05-01T00:00Z"
  val endDateUtc = "2020-05-03T00:00Z"
  s"Given a UTC date of $startDateUtc, falling inside of BST" >> {
    s"When I ask for the inclusive UTC date range between it and $endDateUtc" >> {
      val day1 = "2020-05-01T00:00Z"
      val day2 = "2020-05-02T00:00Z"
      val day3 = "2020-05-03T00:00Z"
      s"I should get $day1, $day2, $day3" >> {
        val dates = Crunch.utcDaysInPeriod(SDate(startDateUtc), SDate(endDateUtc)).map(SDate(_).millisSinceEpoch)
        val expected = Seq(day1, day2, day3).map(SDate(_).millisSinceEpoch)
        dates === expected
      }
    }
  }
  s"Given a UTC date of $startDateUtc, falling inside of BST, and parsed to UTC" >> {
    s"When I ask for the inclusive UTC date range between it and $endDateUtc, and parsed to UTC" >> {
      val day1 = "2020-05-01T00:00Z"
      val day2 = "2020-05-02T00:00Z"
      val day3 = "2020-05-03T00:00Z"
      s"I should get $day1, $day2, $day3" >> {
        val dates = Crunch.utcDaysInPeriod(SDate(startDateUtc, Crunch.utcTimeZone), SDate(endDateUtc, Crunch.utcTimeZone)).map(SDate(_).millisSinceEpoch)
        val expected = Seq(day1, day2, day3).map(SDate(_).millisSinceEpoch)
        dates === expected
      }
    }
  }
  val startDateBst = "2020-05-01T00:00+01"
  val endDateBst = "2020-05-03T00:00+01"
  s"Given a BST date of $startDateBst, falling inside of BST" >> {
    s"When I ask for the inclusive UTC date range between it and $endDateBst" >> {
      val day1 = "2020-04-30T00:00Z"
      val day2 = "2020-05-01T00:00Z"
      val day3 = "2020-05-02T00:00Z"
      s"I should get $day1, $day2, $day3 (because the 1hr offset pushed each date to the date before)" >> {
        val dates = Crunch.utcDaysInPeriod(SDate(startDateBst), SDate(endDateBst)).map(SDate(_).millisSinceEpoch)
        val expected = Seq(day1, day2, day3).map(SDate(_).millisSinceEpoch)
        dates === expected
      }
    }
  }
  s"Given a BST date of $startDateBst, falling inside of BST, and parsed to Europe/London" >> {
    s"When I ask for the inclusive UTC date range between it and $endDateBst, and parsed to Europe/London" >> {
      val day1 = "2020-04-30T00:00Z"
      val day2 = "2020-05-01T00:00Z"
      val day3 = "2020-05-02T00:00Z"
      s"I should get $day1, $day2, $day3 (because the 1hr offset pushed each date to the date before) - The timezone of the SDate should not impact the utc days" >> {
        val dates = Crunch.utcDaysInPeriod(SDate(startDateBst, Crunch.europeLondonTimeZone), SDate(endDateBst, Crunch.europeLondonTimeZone)).map(SDate(_).millisSinceEpoch)
        val expected = Seq(day1, day2, day3).map(SDate(_).millisSinceEpoch)
        dates === expected
      }
    }
  }
}
