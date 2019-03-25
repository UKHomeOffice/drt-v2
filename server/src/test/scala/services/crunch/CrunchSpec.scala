package services.crunch

import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import services.graphstages.Crunch

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
    "Then the last millisecond should be 1339 minutes after the first, ie 1339 * oneMinuteMillis" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L)

    val lastMillis: MillisSinceEpoch = 1439 * Crunch.oneMinuteMillis

    millisFor24Hours.reverse.head === lastMillis
  }
}
