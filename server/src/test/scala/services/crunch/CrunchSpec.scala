package services.crunch

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes._
import uk.gov.homeoffice.drt.ports.Terminals.T1
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch.crunchStartWithOffset

class CrunchSpec extends Specification {
  "When I ask for minuteInADay " +
    "Then I should see 60 * 24 (1440)" >> {
    minutesInADay === 60 * 24
  }

  "When I ask for oneMinuteMillis " +
    "Then I should see 60 * 1000" >> {
    oneMinuteMillis === 60 * 1000
  }

  "When I ask for oneHourMillis " +
    "Then I should see 60 * 60 * 1000" >> {
    oneHourMillis === 60 * 60 * 1000
  }

  "When I ask for oneDayMillis " +
    "Then I should see 60 * 60 * 24 * 1000" >> {
    oneDayMillis === 60 * 60 * 24 * 1000
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then I should see 60 * 24 (1440) milliseconds" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L).toList

    val minutesInADay = 60 * 24

    millisFor24Hours.length === minutesInADay
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then the first millisecond should be zero milliseconds" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L).toList

    val firstMillis: MillisSinceEpoch = 0L

    millisFor24Hours.head === firstMillis
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then the second millisecond should be one minute of milliseconds, ie 60 * 1000, or oneMinuteMillis" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L).toList

    val secondMillis: MillisSinceEpoch = oneMinuteMillis

    millisFor24Hours.drop(1).head === secondMillis
  }

  "Given a start time of zero milliseconds " +
    "When I ask for 24 hours worth of minute milliseconds " +
    "Then the last millisecond should be 1439 minutes after the first, ie 1439 * oneMinuteMillis" >> {
    val millisFor24Hours: Seq[MillisSinceEpoch] = Crunch.minuteMillisFor24hours(0L).toList

    val lastMillis: MillisSinceEpoch = 1439 * oneMinuteMillis

    millisFor24Hours.reverse.head === lastMillis
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for a given range " +
    "Then I should only see milliseconds lying on whole minute boundaries" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List(T1), 1)

    val nonMinuteBoundaryMilliseconds = minutes.filter(_ % oneMinuteMillis != 0)

    nonMinuteBoundaryMilliseconds.isEmpty === true
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for a given range " +
    "Then I the first millisecond should be the previous midnight" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List(T1), 1)

    val previousMidnightMillis = SDate("2019-01-01T00:00:00Z").millisSinceEpoch

    minutes.toSeq.min === previousMidnightMillis
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for one day " +
    "Then I should see 1440 minutes, ie one day's worth" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List(T1), 1)

    minutes.size === minutesInADay
  }

  "Given a mock minute-exists function always returning false " +
    "When I ask for the missing minutes for a given range " +
    "Then I the last millisecond should be one minute before the following midnight" >> {
    val millisecondInDay = SDate("2019-01-01T13:12:11Z").millisSinceEpoch
    val minutes = Crunch.missingMinutesForDay(millisecondInDay, (_, _) => false, List(T1), 1)

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

  "Given 2019-01-01T02:00 with a crunch offset of 120 minutes " +
    "The crunch start should be 2019-01-01T02:00" >> {
    val timeInQuestion = SDate("2019-01-01T02:00")
    val crunchStart = crunchStartWithOffset(120)(timeInQuestion)

    val expected = SDate("2019-01-01T02:00")

    crunchStart.millisSinceEpoch === expected.millisSinceEpoch
  }

  "Given 2019-01-01T01:59 with a crunch offset of 120 minutes " +
    "The crunch start should be 2018-12-31T02:00" >> {
    val timeInQuestion = SDate("2019-01-01T01:59")
    val crunchStart = crunchStartWithOffset(120)(timeInQuestion)

    val expected = SDate("2018-12-31T02:00")

    crunchStart.toISOString === expected.toISOString
  }

  "Given 2019-01-01T02:01:33 with a crunch offset of 120 minutes " +
    "The crunch start should be 2018-12-31T02:00" >> {
    val timeInQuestion = SDate("2019-01-01T02:01")
    val crunchStart = crunchStartWithOffset(120)(timeInQuestion)

    val expected = SDate("2019-01-01T02:00")

    crunchStart.toISOString === expected.toISOString
  }

  "Given a now of 2020-06-01T00:00 BST" >> {
    val now = SDate("2020-06-01T12:00", Crunch.europeLondonTimeZone)
    "When I ask isHistoric for the same date" >> {
      "Then I should get false" >> {
        val isHistoric = now.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone)
      "Then I should get false" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for 1 minute before BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone).addMinutes(-1)
      "Then I should get true" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === true
      }
    }
  }

  "Given a now of 2020-06-01T12:00 BST" >> {
    val now = SDate("2020-06-01T12:00", Crunch.europeLondonTimeZone)
    "When I ask isHistoric for the same date" >> {
      "Then I should get false" >> {
        val isHistoric = now.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone)
      "Then I should get false" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for 1 minute before BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone).addMinutes(-1)
      "Then I should get true" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === true
      }
    }
  }
}
