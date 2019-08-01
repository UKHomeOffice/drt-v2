package services.crunch

import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import org.joda.time.DateTime

class CrunchSpec extends Specification {
  "When I ask for minuteInADay " +
    "Then I should see 60 * 24 (1440)" >> {
    Crunch.minutesInADay === 60 * 24
  }

  "When I ask for oneMinuteMillis " +
    "Then I should see 60 * 1000" >> {
    Crunch.oneMinuteMillis === 60 * 1000
  }

  "When I ask for oneHourMillis " +
    "Then I should see 60 * 60 * 1000" >> {
    Crunch.oneHourMillis === 60 * 60 * 1000
  }

  "When I ask for oneDayMillis " +
    "Then I should see 60 * 60 * 24 * 1000" >> {
    Crunch.oneDayMillis === 60 * 60 * 24 * 1000
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then I should see 60 * 24 (1440) milliseconds" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L)

    val minutesInADay = 60 * 24

    millisFor24Hours.length === minutesInADay
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then the first millisecond should be zero milliseconds" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L)

    val firstMillis: MillisSinceEpoch = 0L

    millisFor24Hours.head === firstMillis
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then the second millisecond should be one minute of milliseconds, ie 60 * 1000, or oneMinuteMillis" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L)

    val secondMillis: MillisSinceEpoch = Crunch.oneMinuteMillis

    millisFor24Hours.drop(1).head === secondMillis
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then the last millisecond should be 1439 minutes after the first, ie 1439 * oneMinuteMillis" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L)

    val lastMillis: MillisSinceEpoch = 1439 * Crunch.oneMinuteMillis

    millisFor24Hours.reverse.head === lastMillis
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for a given range " +
    "Then I should only see milliseconds lying on whole minute boundaries" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List("T1"), 1)

    val nonMinuteBoundaryMilliseconds = minutes.filter(_ % Crunch.oneMinuteMillis != 0)

    nonMinuteBoundaryMilliseconds.isEmpty === true
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for a given range " +
    "Then I the first millisecond should be the previous midnight" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List("T1"), 1)

    val previousMidnightMillis = SDate("2019-01-01T00:00:00Z").millisSinceEpoch

    minutes.toSeq.min === previousMidnightMillis
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for one day " +
    "Then I should see 1440 minutes, ie one day's worth" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List("T1"), 1)

    minutes.size === Crunch.minutesInADay
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for a given range " +
    "Then I the last millisecond should be one minute before the following midnight" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List("T1"), 1)

    val oneMinuteBeforeNextMidnight = SDate("2019-01-01T23:59:00Z").millisSinceEpoch

    minutes.toSeq.max === oneMinuteBeforeNextMidnight
  }

  "isInRangeOnDay should be True of the last date-time in a given date range" >> {
    val anHourAgo: DateTime = DateTime.now.minusHours(1)
    val now: DateTime = DateTime.now
    val startDateTime = SDate(anHourAgo)
    val endDateTime = SDate(now)
    Crunch.isInRangeOnDay(startDateTime, endDateTime)(endDateTime) must beTrue
  }
}
